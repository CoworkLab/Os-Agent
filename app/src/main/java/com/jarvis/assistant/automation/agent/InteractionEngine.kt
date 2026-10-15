package com.jarvis.assistant.automation.agent

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import kotlinx.coroutines.delay

/** How a click was ultimately delivered, for honest reporting. */
enum class ClickMethod { ACCESSIBILITY_ACTION, GESTURE_TAP, NONE }

data class ClickOutcome(
    val success: Boolean,
    val method: ClickMethod,
    val targetLabel: String,
    val point: Pair<Int, Int>?,
    val detail: String,
    val verified: Boolean = false
)

data class TypeOutcome(
    val success: Boolean,
    val verified: Boolean,
    val method: String,
    val detail: String,
    val actualText: String? = null
)

/**
 * The agent's hands.
 *
 * Every action here follows the same discipline: resolve an exact target, act, then
 * read the screen back to confirm the action actually took effect. A boolean from
 * the accessibility API means "the call was accepted", not "the UI changed", so
 * nothing is reported as done until the state is re-read.
 */
class InteractionEngine(private val service: JarvisAccessibilityService) {

    companion object {
        private const val TAG = "InteractionEngine"
    }

    // ---------------------------------------------------------------- clicking

    /**
     * Clicks a node with the highest-precision strategy available.
     *
     * Accessibility actions are preferred because they are immune to coordinate
     * drift, overlays and animations. A gesture tap is the fallback, and its
     * completion callback is awaited so a rejected gesture is not mistaken for a hit.
     */
    suspend fun clickNode(
        node: AccessibilityNodeInfo,
        label: String,
        awaitChangeMs: Long = 1_200L
    ): ClickOutcome {
        val before = ScreenSignatureCalculator.computeSignature(service.getActiveRootNode())
        val rect = Rect().also { node.getBoundsInScreen(it) }

        if (rect.width() <= 0 || rect.height() <= 0) {
            return ClickOutcome(false, ClickMethod.NONE, label, null, "Target has no visible bounds")
        }

        // 1. Direct accessibility click.
        val selfClicked = node.isClickable &&
            runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)

        if (selfClicked) {
            val verified = UiSettler.awaitChange(service, before, awaitChangeMs) != null
            return ClickOutcome(
                success = true,
                method = ClickMethod.ACCESSIBILITY_ACTION,
                targetLabel = label,
                point = rect.centerX() to rect.centerY(),
                detail = if (verified) "Clicked via accessibility action; screen changed"
                else "Clicked via accessibility action; no visible change yet",
                verified = verified
            )
        }

        // 2. The nearest clickable ancestor, which is where a row/card actually listens.
        val actionable = nearestActionable(node)
        if (actionable != null && actionable !== node) {
            val clicked = runCatching {
                actionable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }.getOrDefault(false)
            if (clicked) {
                val verified = UiSettler.awaitChange(service, before, awaitChangeMs) != null
                val targetRect = Rect().also { actionable.getBoundsInScreen(it) }
                return ClickOutcome(
                    success = true,
                    method = ClickMethod.ACCESSIBILITY_ACTION,
                    targetLabel = label,
                    point = targetRect.centerX() to targetRect.centerY(),
                    detail = if (verified) "Clicked the clickable ancestor; screen changed"
                    else "Clicked the clickable ancestor; no visible change yet",
                    verified = verified
                )
            }
        }

        // 3. Gesture tap on the exact centre of the target, awaiting the real callback.
        val x = rect.exactCenterX()
        val y = rect.exactCenterY()
        val outcome = GestureDispatcher.tap(service, x, y)
        if (!outcome.succeeded) {
            return ClickOutcome(
                false, ClickMethod.GESTURE_TAP, label,
                x.toInt() to y.toInt(), outcome.detail
            )
        }
        val verified = UiSettler.awaitChange(service, before, awaitChangeMs) != null
        return ClickOutcome(
            success = true,
            method = ClickMethod.GESTURE_TAP,
            targetLabel = label,
            point = x.toInt() to y.toInt(),
            detail = if (verified) "Tapped at (${x.toInt()}, ${y.toInt()}); screen changed"
            else "Tapped at (${x.toInt()}, ${y.toInt()}); no visible change",
            verified = verified
        )
    }

    /**
     * Clicks a raw screen coordinate. Used when vision identifies a target that the
     * accessibility tree does not expose (canvas, game surface, WebView internals).
     */
    suspend fun clickPoint(x: Int, y: Int, awaitChangeMs: Long = 1_200L): ClickOutcome {
        val before = ScreenSignatureCalculator.computeSignature(service.getActiveRootNode())
        val outcome = GestureDispatcher.tap(service, x.toFloat(), y.toFloat())
        if (!outcome.succeeded) {
            return ClickOutcome(false, ClickMethod.GESTURE_TAP, "($x, $y)", x to y, outcome.detail)
        }
        val verified = UiSettler.awaitChange(service, before, awaitChangeMs) != null
        return ClickOutcome(
            success = true,
            method = ClickMethod.GESTURE_TAP,
            targetLabel = "($x, $y)",
            point = x to y,
            detail = outcome.detail,
            verified = verified
        )
    }

    /**
     * Finds the closest ancestor that can receive a click, preferring the smallest
     * one so the tap does not land on a huge scroll container that swallows it.
     */
    fun nearestActionable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (isActionable(node)) return node
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 6) {
            if (isActionable(parent)) return parent
            parent = parent.parent
            depth++
        }
        return null
    }

    private fun isActionable(node: AccessibilityNodeInfo): Boolean =
        node.isClickable || node.isLongClickable ||
            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }

    // ---------------------------------------------------------------- typing

    /**
     * Types text into a field and proves the field actually holds it.
     *
     * Two strategies are used, in order of reliability:
     *  1. ACTION_SET_TEXT — exact, needs no keyboard, works on most fields.
     *  2. Clipboard paste via ACTION_PASTE — for fields that reject programmatic
     *     text (some WebViews, password fields, custom editors).
     *
     * Per-character key injection is deliberately not attempted: the public
     * accessibility API has no key-code action (ACTION_ARGUMENT_KEY_CODE is hidden),
     * and faking it would report success without typing anything.
     *
     * Nothing is reported as typed until the field's own value is read back.
     */
    suspend fun typeInto(
        target: AccessibilityNodeInfo,
        text: String
    ): TypeOutcome {
        if (text.isEmpty()) return TypeOutcome(false, false, "none", "Refused to type empty text")

        // A field that never took focus silently drops input, so focus it first.
        val focused = ensureFocused(target)
        if (!focused) Log.w(TAG, "Could not focus the target field; attempting to type anyway")
        delay(60)

        // Strategy 1: ACTION_SET_TEXT.
        val setText = runCatching {
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
            target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }.getOrDefault(false)

        if (setText && readBackEquals(target, text)) {
            return TypeOutcome(true, true, "ACTION_SET_TEXT", "Text set and verified", text)
        }

        // Strategy 2: clipboard paste, only after clearing whatever is already there
        // so the new text cannot end up appended to stale content.
        val pasteOutcome = pasteInto(target, text)
        if (pasteOutcome != null && readBackEquals(target, text)) {
            return TypeOutcome(true, true, "clipboard-paste", pasteOutcome, text)
        }

        val actual = readText(target)
        return TypeOutcome(
            success = false,
            verified = false,
            method = "ACTION_SET_TEXT",
            detail = buildString {
                append("Field value did not match what was requested.")
                if (!focused) append(" The field could not be focused.")
                if (!setText) append(" The field rejected programmatic text.")
            },
            actualText = actual
        )
    }

    /**
     * Pastes text through the system clipboard. Returns a note on success, or null
     * when paste was unavailable so the caller can report an honest failure.
     */
    private suspend fun pasteInto(target: AccessibilityNodeInfo, text: String): String? {
        val clipboard = service.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager ?: return null
        return try {
            // Clear first; otherwise paste appends to existing content.
            if (!target.text.isNullOrEmpty()) {
                val clearArgs = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                }
                target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
                delay(90)
            }
            clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText("jarvis_input", text)
            )
            val pasted = runCatching {
                target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            }.getOrDefault(false)
            if (!pasted) return null
            delay(140)
            "Pasted from clipboard and verified"
        } catch (e: Exception) {
            Log.w(TAG, "Clipboard paste failed: ${e.message}")
            null
        }
    }

    /** Focuses an editable node, using ACTION_FOCUS then a tap as a last resort. */
    private suspend fun ensureFocused(target: AccessibilityNodeInfo): Boolean {
        if (target.isFocused) return true
        if (runCatching { target.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }.getOrDefault(false)) {
            delay(60)
            if (target.isFocused) return true
        }
        // Clicking the field is how a real user focuses it, so try that before giving up.
        val rect = Rect().also { target.getBoundsInScreen(it) }
        if (rect.width() > 0 && rect.height() > 0) {
            GestureDispatcher.tap(service, rect.exactCenterX(), rect.exactCenterY())
            delay(160)
            val focused = service.getActiveRootNode()
                ?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null) return true
        }
        return false
    }

    /**
     * Reads a node's current value.
     *
     * Submitting after typing is intentionally not automatic: the public
     * accessibility API cannot synthesise an Enter key (the key-code arguments are
     * hidden), and tapping "whatever is nearby" is how the wrong button gets pressed.
     * Callers must resolve the real send or search control and click it as its own
     * verified action.
     */
    fun readText(node: AccessibilityNodeInfo?): String? =
        node?.text?.toString()?.replace("\r\n", "\n")

    /**
     * Compares the field's current value with what was requested. Whitespace is
     * normalised because IMEs and autocomplete frequently reflow it.
     */
    fun readBackEquals(node: AccessibilityNodeInfo?, expected: String): Boolean {
        val actual = readText(node) ?: return false
        fun norm(s: String) = s.replace("\r\n", "\n").replace(Regex("\\s+"), " ").trim()
        return norm(actual) == norm(expected)
    }

    // ---------------------------------------------------------------- scrolling

    /**
     * Scrolls a container and reports whether anything moved.
     *
     * ACTION_SCROLL_FORWARD is used first because it is handled by the container
     * itself and cannot be misread as a fling. Only when no scrollable node is
     * present does it fall back to a gesture drag.
     */
    suspend fun scrollOnce(
        scrollable: AccessibilityNodeInfo?,
        direction: String,
        distance: ScrollDistance = ScrollDistance.MEDIUM
    ): Boolean {
        val before = ScreenSignatureCalculator.computeSignature(service.getActiveRootNode())

        if (scrollable != null && scrollable.isScrollable) {
            val actionId = when (direction.lowercase()) {
                "up" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.id
                "left" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.id
                else -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id
            }
            val scrolled = runCatching { scrollable.performAction(actionId) }.getOrDefault(false)
            if (scrolled) {
                val changed = UiSettler.awaitChange(service, before, 900L) != null
                if (changed) return true
            }
        }

        // Gesture fallback, sized to the requested distance.
        val metrics = service.resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        val delta = h * distance.fraction
        val (fromX, fromY, toX, toY) = when (direction.lowercase()) {
            "up" -> listOf(w * 0.5f, h * 0.40f, w * 0.5f, h * 0.40f + delta)
            "left" -> listOf(w * 0.80f, h * 0.5f, w * 0.80f - w * distance.fraction, h * 0.5f)
            "right" -> listOf(w * 0.20f, h * 0.5f, w * 0.20f + w * distance.fraction, h * 0.5f)
            else -> listOf(w * 0.5f, h * 0.60f, w * 0.5f, h * 0.60f - delta)
        }

        val outcome = GestureDispatcher.drag(service, fromX, fromY, toX, toY, distance.durationMs)
        if (!outcome.succeeded) return false
        return UiSettler.awaitChange(service, before, 1_000L) != null
    }

    /** Finds the outermost scrollable container in the active window. */
    fun findScrollable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        if (root.isScrollable) return root
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 400) {
            val node = queue.removeFirst()
            visited++
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                if (child.isScrollable) return child
                queue.add(child)
            }
        }
        return null
    }
}

/** Scroll amounts expressed as a fraction of the screen so they adapt to any device. */
enum class ScrollDistance(val fraction: Float, val durationMs: Long) {
    SMALL(0.18f, 320L),
    MEDIUM(0.35f, 420L),
    LARGE(0.55f, 520L)
}
