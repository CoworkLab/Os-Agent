package com.jarvis.assistant.automation.agent

import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.data.provider.ChatMessage
import com.jarvis.assistant.data.provider.ImageAttachment
import com.jarvis.assistant.data.provider.ProviderResult
import com.jarvis.assistant.vision.ScreenCaptureManager
import com.jarvis.assistant.vision.UiElementInfo
import com.jarvis.assistant.vision.UiGroundingEngine
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * How the agent looked at the screen for one turn.
 *
 * Both channels are always attempted; whichever is unavailable is recorded as a
 * reason so the model is told what it is missing rather than being handed an empty
 * screen and left to guess.
 */
data class ScreenObservation(
    val elements: List<UiElementInfo>,
    val elementsSummary: String,
    val image: ImageAttachment?,
    val imageWidth: Int,
    val imageHeight: Int,
    val packageName: String,
    val signature: ScreenSignature?,
    val visionUsed: Boolean,
    val visionSkipReason: String?,
    val capturedAt: Long
) {
    val hasElements: Boolean get() = elements.isNotEmpty()
    val hasImage: Boolean get() = image != null
}

/**
 * The agent's eyes.
 *
 * A person looking at a phone uses two senses at once: they read the labelled
 * controls and they look at the pixels. Neither alone is sufficient — the
 * accessibility tree misses canvas, games, video and many WebViews, while vision
 * alone cannot read a value that is present as text but rendered off-screen.
 * This class gathers both and, when both are present, asks the model to reconcile
 * them instead of silently trusting one.
 */
class ScreenPerceiver(private val service: JarvisAccessibilityService) {

    companion object {
        private const val TAG = "ScreenPerceiver"

        /** Downscale target. Large frames cost tokens and add nothing for UI work. */
        private const val MAX_IMAGE_DIMENSION = 1024

        private const val JPEG_QUALITY = 70
    }

    private val grounding = UiGroundingEngine()

    /** Reads the accessibility tree and, when allowed, grabs one real frame. */
    suspend fun observe(withVision: Boolean = true): ScreenObservation {
        val root = service.getActiveRootNode() ?: service.getAppRootNode()
        val elements = grounding.scanScreen(root)
        val signature = ScreenSignatureCalculator.computeSignature(root)
        val packageName = root?.packageName?.toString().orEmpty()

        var image: ImageAttachment? = null
        var width = 0
        var height = 0
        var skipReason: String? = null

        if (!withVision) {
            skipReason = "Vision is switched off for this model, so only the accessibility tree is available."
        } else if (!JarvisApp.instance.preferences.canUseVisionNow(
                JarvisApp.instance.activeProviderKind,
                JarvisApp.instance.preferences.providerModel(JarvisApp.instance.activeProviderKind)
            )
        ) {
            skipReason = "This model has not passed the vision test, so only the accessibility tree is available."
        } else {
            val frame = captureFrame()
            if (frame == null) {
                skipReason = "Screen Share is not running, so no pixels are available. The accessibility tree is used alone."
            } else {
                image = frame.first
                width = frame.second
                height = frame.third
            }
        }

        return ScreenObservation(
            elements = elements,
            elementsSummary = grounding.formatElementsSummary(elements, root),
            image = image,
            imageWidth = width,
            imageHeight = height,
            packageName = packageName,
            signature = signature,
            visionUsed = image != null,
            visionSkipReason = skipReason,
            capturedAt = android.os.SystemClock.uptimeMillis()
        )
    }

    /**
     * Grabs the newest screen frame as a base64 JPEG, downscaled for the model.
     *
     * The capture pipeline already produces JPEG bytes; they are decoded only to
     * resize, because sending a full-resolution tablet screenshot wastes most of the
     * context window on pixels that carry no UI information.
     */
    private fun captureFrame(): Triple<ImageAttachment, Int, Int>? {
        val manager = ScreenCaptureManager.getInstance()
        if (!manager.isScreenShareActive()) return null

        val raw = try {
            manager.captureSnapshotJpeg(JPEG_QUALITY)
        } catch (e: Exception) {
            Log.w(TAG, "Frame capture failed: ${e.message}")
            null
        } ?: return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val scale = minOf(
            MAX_IMAGE_DIMENSION.toFloat() / bounds.outWidth,
            MAX_IMAGE_DIMENSION.toFloat() / bounds.outHeight,
            1f
        )

        if (scale >= 1f) {
            return Triple(
                ImageAttachment(Base64.encodeToString(raw, Base64.NO_WRAP), "image/jpeg"),
                bounds.outWidth,
                bounds.outHeight
            )
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = Integer.highestOneBit((1f / scale).toInt().coerceAtLeast(1))
        }
        val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size, decodeOptions) ?: return null
        val scaled = android.graphics.Bitmap.createScaledBitmap(
            bitmap,
            (bounds.outWidth * scale).toInt().coerceAtLeast(1),
            (bounds.outHeight * scale).toInt().coerceAtLeast(1),
            true
        )
        val stream = java.io.ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        if (scaled !== bitmap) bitmap.recycle()
        scaled.recycle()

        val bytes = stream.toByteArray()
        return Triple(
            ImageAttachment(Base64.encodeToString(bytes, Base64.NO_WRAP), "image/jpeg"),
            (bounds.outWidth * scale).toInt(),
            (bounds.outHeight * scale).toInt()
        )
    }
}

/**
 * The agent's inner monologue: the loop that decides what to do next.
 *
 * The LLM is used only to think. It never touches the device. It receives a
 * description of what is on screen, returns a single intended action, and the
 * Android runtime performs that action, verifies it, and reports back what
 * actually happened. This mirrors how a person works: decide, act, look again,
 * and correct when reality disagrees with expectation.
 */
class AgentBrain(private val service: JarvisAccessibilityService) {

    companion object {
        private const val TAG = "AgentBrain"

        /** Upper bound on turns for one goal, so a confused model cannot loop forever. */
        const val MAX_TURNS = 24

        /** Two identical failed turns in a row means the approach must change. */
        private const val REPEAT_FAILURE_LIMIT = 2
    }

    private val perceiver = ScreenPerceiver(service)
    private val engine = InteractionEngine(service)

    /** One decision the model asked for, in a form the runtime can execute. */
    data class PlannedAction(
        val kind: String,
        val target: String?,
        val elementId: Int?,
        val text: String?,
        val direction: String?,
        val region: String?,
        val x: Int?,
        val y: Int?,
        val reason: String,
        val done: Boolean,
        val successSummary: String?
    )

    /** The result of one turn, after the action was performed and verified. */
    data class TurnResult(
        val action: PlannedAction,
        val performed: Boolean,
        val verified: Boolean,
        val detail: String,
        val observation: ScreenObservation
    )

    /**
     * Runs one full turn: look, think, act, verify.
     *
     * Returns null when the goal is complete, so the caller stops looping.
     */
    suspend fun runTurn(
        goal: String,
        history: List<String>,
        allowVision: Boolean
    ): TurnResult? {
        val observation = perceiver.observe(withVision = allowVision)
        val decision = think(goal, history, observation) ?: return null

        if (decision.done) return null

        val (performed, verified, detail) = perform(decision, observation)
        return TurnResult(decision, performed, verified, detail, observation)
    }

    /**
     * Sends the current screen state and the history to the model and parses one
     * action out of the reply.
     *
     * The prompt states the available actions and demands JSON so the reply can be
     * executed mechanically. It also states explicitly which senses were available,
     * because a model that does not know vision is missing will invent what it
     * cannot see.
     */
    private suspend fun think(
        goal: String,
        history: List<String>,
        observation: ScreenObservation
    ): PlannedAction? {
        val app = JarvisApp.instance
        val config = app.activeProviderConfig()
        if (!config.isReady) {
            Log.w(TAG, "Active provider is not ready: ${config.describe}")
            return null
        }

        val prompt = buildPrompt(goal, history, observation)

        val response = app.activeProviderClient().complete(
            baseUrl = config.baseUrl,
            apiKey = config.apiKey,
            model = config.model,
            messages = listOf(ChatMessage(role = "user", content = prompt)),
            image = observation.image,
            maxTokens = 700,
            temperature = 0.2
        )

        return when (response) {
            is ProviderResult.Failure -> {
                Log.e(TAG, "Model call failed: ${response.message}")
                null
            }
            is ProviderResult.Success -> parseDecision(response.value)
        }
    }

    private fun buildPrompt(
        goal: String,
        history: List<String>,
        observation: ScreenObservation
    ): String = buildString {
        appendLine("You are controlling an Android phone by looking at its screen.")
        appendLine("You decide ONE next action. Something else performs it and tells you what happened.")
        appendLine()
        appendLine("GOAL: $goal")
        appendLine()
        appendLine("CURRENT APP: ${observation.packageName.ifBlank { "unknown" }}")
        appendLine()
        appendLine("WHAT IS VISIBLE (accessibility tree, with element ids):")
        appendLine(observation.elementsSummary.ifBlank { "(nothing readable)" })
        appendLine()
        if (observation.visionUsed) {
            appendLine("A screenshot of the current screen is attached. Use it to confirm what you")
            appendLine("see and to find anything missing from the list above.")
        } else {
            appendLine("NO SCREENSHOT IS AVAILABLE. ${observation.visionSkipReason}")
            appendLine("Do not claim to see anything that is not in the list above.")
        }
        appendLine()
        if (history.isNotEmpty()) {
            appendLine("WHAT YOU ALREADY TRIED, AND WHAT ACTUALLY HAPPENED:")
            history.takeLast(8).forEach { appendLine("- $it") }
            appendLine("If something failed, do not repeat it unchanged.")
            appendLine()
        }
        appendLine(
            """
            Reply with JSON only, no prose:
            {
              "reason": "why this is the right next step",
              "action": "click" | "type" | "scroll" | "back" | "home" | "open_app" | "wait" | "move_avatar" | "done",
              "element_id": <id from the list, for click/type>,
              "target": "<visible label, when there is no element id>",
              "text": "<text to type>",
              "direction": "up" | "down" | "left" | "right",
              "x": <pixel x, only if you can see the target but it has no element id>,
              "y": <pixel y, only if you can see the target but it has no element id>,
              "done": true | false,
              "success_summary": "<only when done is true>"
            }
            Rules:
            - Prefer "element_id" when the target appears in the list.
            - Use x/y only when you can see the target in the screenshot but it is not listed.
            - To type, name the input field, then send the text as a separate step if needed.
            - Set "done" true only when the goal is genuinely finished.
            """.trimIndent()
        )
    }

    /** Parses the model's JSON into a planned action, tolerating code fences. */
    private fun parseDecision(raw: String): PlannedAction? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) {
            Log.w(TAG, "Model reply contained no JSON object")
            return null
        }
        val json = runCatching { JSONObject(raw.substring(start, end + 1)) }.getOrNull() ?: return null

        return PlannedAction(
            kind = json.optString("action", "wait").lowercase(Locale.ROOT),
            target = json.optString("target").takeIf { it.isNotBlank() },
            elementId = if (json.has("element_id")) json.optInt("element_id", -1).takeIf { it > 0 } else null,
            text = json.optString("text").takeIf { it.isNotBlank() },
            direction = json.optString("direction").takeIf { it.isNotBlank() },
            region = json.optString("region").takeIf { it.isNotBlank() },
            x = if (json.has("x")) json.optInt("x", -1).takeIf { it >= 0 } else null,
            y = if (json.has("y")) json.optInt("y", -1).takeIf { it >= 0 } else null,
            reason = json.optString("reason"),
            done = json.optBoolean("done", false),
            successSummary = json.optString("success_summary").takeIf { it.isNotBlank() }
        )
    }

    /**
     * Carries out one planned action and reports what really happened.
     *
     * Every branch resolves a concrete target before acting and verifies afterwards.
     * A failure is reported as a failure with the reason, which is what lets the next
     * turn choose something different instead of repeating a broken step.
     */
    private suspend fun perform(
        action: PlannedAction,
        observation: ScreenObservation
    ): Triple<Boolean, Boolean, String> {
        // The tree may have changed since the observation; re-read it so element ids
        // and bounds refer to what is on screen right now.
        val freshElements = groundingElements()
        val target = action.elementId?.let { id -> freshElements.firstOrNull { it.id == id } }
            ?: action.target?.let { name -> freshElements.firstOrNull { matches(it, name) } }

        return when (action.kind) {
            "click" -> {
                val outcome = when {
                    target?.node != null -> engine.clickNode(target.node, target.primaryLabel)
                    action.x != null && action.y != null -> engine.clickPoint(action.x, action.y)
                    target != null -> engine.clickPoint(target.bounds.centerX(), target.bounds.centerY())
                    else -> null
                }
                if (outcome == null) {
                    Triple(false, false, "Nothing on screen matched '${action.target ?: action.elementId}'. Look again and pick a target that exists.")
                } else {
                    Triple(outcome.success, outcome.verified, outcome.detail)
                }
            }

            "type" -> {
                val field = target?.takeIf { it.isEditable || it.isClickable } ?: freshElements.firstOrNull { it.isEditable }
                if (field?.node == null) {
                    Triple(false, false, "No editable field matched '${action.target}'. Tap the field first, then type.")
                } else {
                    val outcome = engine.typeInto(field.node, action.text.orEmpty())
                    Triple(outcome.success, outcome.verified, outcome.detail)
                }
            }

            "scroll" -> {
                val scrollable = target?.node?.takeIf { it.isScrollable }
                    ?: engine.findScrollable(service.getActiveRootNode())
                val moved = engine.scrollOnce(scrollable, action.direction ?: "down")
                Triple(moved, moved, if (moved) "Scrolled ${action.direction ?: "down"}; new content is visible." else "Scrolling produced no visible change; the list may already be at its end.")
            }

            "back" -> {
                val ok = service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                if (ok) UiSettler.awaitStable(service, 1_500L) else null
                Triple(ok, ok, if (ok) "Pressed Back." else "Back was not accepted.")
            }

            "home" -> {
                val ok = service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                if (ok) UiSettler.awaitStable(service, 1_500L) else null
                Triple(ok, ok, if (ok) "Went to the home screen." else "Home was not accepted.")
            }

            "open_app" -> {
                val label = action.target ?: action.text
                val opened = label != null && launchApp(label)
                Triple(opened, opened, if (opened) "Opened $label." else "Could not find an app called '$label'.")
            }

            "move_avatar" -> {
                val moved = com.jarvis.assistant.background.JarvisConversationService.taskOverlay
                    ?.moveAvatar(action.region, action.x, action.y) == true
                Triple(moved, moved, if (moved) "Moved the avatar out of the way." else "The avatar could not be moved.")
            }

            "wait" -> {
                UiSettler.awaitStable(service, 2_500L)
                Triple(true, true, "Waited for the screen to settle.")
            }

            else -> Triple(false, false, "Unsupported action '${action.kind}'.")
        }
    }

    private fun groundingElements(): List<UiElementInfo> =
        UiGroundingEngine().scanScreen(service.getActiveRootNode() ?: service.getAppRootNode())

    /**
     * Opens an installed app by matching its launcher label, case-insensitively.
     * Exact matches win, then prefix matches, so "Chrome" does not open "Chrome Beta"
     * when both are installed but the exact name is present.
     */
    private fun launchApp(label: String): Boolean {
        val query = label.lowercase(Locale.ROOT).trim()
        val packageManager = service.packageManager
        val launcherIntent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)

        val candidates = packageManager.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { resolveInfo ->
                val name = resolveInfo.loadLabel(packageManager).toString()
                val pkg = resolveInfo.activityInfo?.packageName
                if (pkg == null) null else Triple(name, pkg, resolveInfo.activityInfo.name)
            }

        val chosen = candidates.firstOrNull { it.first.equals(query, ignoreCase = true) }
            ?: candidates.firstOrNull { it.first.lowercase(Locale.ROOT).startsWith(query) }
            ?: candidates.firstOrNull { it.first.lowercase(Locale.ROOT).contains(query) }

        if (chosen == null) return false
        return try {
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                .setClassName(chosen.second, chosen.third)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            service.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not launch ${chosen.second}: ${e.message}")
            false
        }
    }

    /** Loose label match so "Send" finds "Send message" without matching "Settings". */
    private fun matches(element: UiElementInfo, query: String): Boolean {
        val q = query.lowercase(Locale.ROOT).trim()
        if (q.isEmpty()) return false
        val labels = listOf(element.text, element.contentDescription, element.viewId.substringAfterLast("/"))
            .map { it.lowercase(Locale.ROOT) }
            .filter { it.isNotBlank() }
        return labels.any { it == q } ||
            labels.any { it.contains(q) } ||
            labels.any { q.contains(it) && it.length >= 3 }
    }
}

/**
 * One goal being worked on, with memory of what was already tried.
 *
 * A person does not forget their last failed tap the moment they try again. This
 * keeps the turn history so the model is told what it already attempted and what
 * actually happened, which is what stops it from repeating a step that does not
 * work. The history is bounded so the prompt cannot grow without limit.
 */
class AgentSession(
    private val service: JarvisAccessibilityService,
    val goal: String
) {
    private val brain = AgentBrain(service)
    private val history = mutableListOf<String>()
    private var lastFailedSignature: String? = null
    private var repeatFailureCount = 0

    val turns: Int get() = history.size

    /**
     * Runs one look-think-act-verify turn.
     *
     * Returns null when the model reported the goal finished, or when the same
     * action keeps failing against an unchanging screen — at that point looping
     * again would only burn tokens, so the caller is told to stop.
     */
    suspend fun step(goalOverride: String? = null, allowVision: Boolean = true): AgentBrain.TurnResult? {
        if (history.size >= AgentBrain.MAX_TURNS) {
            history.add("Stopped: reached the ${AgentBrain.MAX_TURNS}-turn limit for this goal.")
            return null
        }

        val activeGoal = goalOverride?.takeIf { it.isNotBlank() } ?: goal
        val turn = brain.runTurn(activeGoal, history, allowVision) ?: run {
            history.add("The goal was reported complete.")
            return null
        }

        history.add(describe(turn))

        // Detect being stuck: the same action failing against an identical screen.
        val fingerprint = "${turn.action.kind}|${turn.action.elementId}|${turn.action.target}|${turn.observation.signature?.contentHash}"
        if (!turn.verified) {
            if (fingerprint == lastFailedSignature) {
                repeatFailureCount++
                if (repeatFailureCount >= 2) {
                    history.add("Stopped: the same action failed twice on an unchanged screen.")
                    return null
                }
            } else {
                repeatFailureCount = 0
            }
            lastFailedSignature = fingerprint
        } else {
            repeatFailureCount = 0
            lastFailedSignature = null
        }

        return turn
    }

    /** Turns one outcome into a short line the next prompt can include verbatim. */
    private fun describe(turn: AgentBrain.TurnResult): String {
        val target = turn.action.target ?: turn.action.elementId?.let { "element $it" } ?: ""
        val verdict = when {
            turn.verified -> "worked"
            turn.performed -> "ran but the screen did not change as expected"
            else -> "did not take effect"
        }
        return "${turn.action.kind} $target -> $verdict (${turn.detail})"
    }

    /** Clears the turn history so a new goal starts with a clean slate. */
    fun reset() {
        history.clear()
        lastFailedSignature = null
        repeatFailureCount = 0
    }
}
