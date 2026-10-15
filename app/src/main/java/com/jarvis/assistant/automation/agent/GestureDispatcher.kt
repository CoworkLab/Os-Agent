package com.jarvis.assistant.automation.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Outcome of a dispatched gesture, including how long the platform took. */
data class GestureOutcome(
    val dispatched: Boolean,
    val completed: Boolean,
    val durationMs: Long,
    val detail: String
) {
    val succeeded: Boolean get() = dispatched && completed
}

/**
 * Dispatches touch gestures and waits for the platform to report completion.
 *
 * The accessibility API delivers its verdict through
 * [AccessibilityService.GestureResultCallback]. Passing `null` there — as the older
 * code did — only tells us the gesture was *accepted for delivery*, which is why a
 * tap that landed on nothing still looked like a success. Every call here waits for
 * the real callback so callers can distinguish "sent" from "happened".
 */
object GestureDispatcher {

    private const val TAG = "GestureDispatcher"

    /** Minimum stroke duration the platform honours; anything shorter is dropped. */
    private const val MIN_STROKE_MS = 30L

    suspend fun tap(
        service: AccessibilityService,
        x: Float,
        y: Float,
        holdMs: Long = 40L,
        timeoutMs: Long = 2_000L
    ): GestureOutcome = stroke(service, x, y, x, y, holdMs, timeoutMs, "tap")

    suspend fun longPress(
        service: AccessibilityService,
        x: Float,
        y: Float,
        holdMs: Long = 650L,
        timeoutMs: Long = 3_000L
    ): GestureOutcome = stroke(service, x, y, x, y, holdMs, timeoutMs, "long-press")

    suspend fun swipe(
        service: AccessibilityService,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long = 320L,
        timeoutMs: Long = 4_000L
    ): GestureOutcome = stroke(service, fromX, fromY, toX, toY, durationMs, timeoutMs, "swipe")

    /**
     * Multi-point swipe used for scroll flings. Points are interpolated along the
     * line so the platform sees a smooth path rather than a single jump, which is
     * what makes long scrolls behave like a finger drag instead of a flick.
     */
    suspend fun drag(
        service: AccessibilityService,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long = 450L,
        steps: Int = 12,
        timeoutMs: Long = 5_000L
    ): GestureOutcome {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return GestureOutcome(false, false, 0, "Gesture dispatch requires Android N or newer")
        }
        val safeDuration = durationMs.coerceAtLeast(MIN_STROKE_MS)
        val path = Path().apply {
            moveTo(fromX, fromY)
            val count = steps.coerceAtLeast(2)
            for (i in 1..count) {
                val t = i.toFloat() / count
                lineTo(fromX + (toX - fromX) * t, fromY + (toY - fromY) * t)
            }
        }
        return dispatch(service, path, safeDuration, timeoutMs, "drag")
    }

    private suspend fun stroke(
        service: AccessibilityService,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long,
        timeoutMs: Long,
        label: String
    ): GestureOutcome {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return GestureOutcome(false, false, 0, "Gesture dispatch requires Android N or newer")
        }
        val path = Path().apply {
            moveTo(fromX, fromY)
            if (fromX != toX || fromY != toY) lineTo(toX, toY)
        }
        return dispatch(service, path, durationMs.coerceAtLeast(MIN_STROKE_MS), timeoutMs, label)
    }

    private suspend fun dispatch(
        service: AccessibilityService,
        path: Path,
        durationMs: Long,
        timeoutMs: Long,
        label: String
    ): GestureOutcome {
        val startedAt = SystemClock.uptimeMillis()

        val outcome = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                    .build()

                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) {
                            continuation.resume(
                                GestureOutcome(
                                    dispatched = true,
                                    completed = true,
                                    durationMs = SystemClock.uptimeMillis() - startedAt,
                                    detail = "$label completed"
                                )
                            )
                        }
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) {
                            continuation.resume(
                                GestureOutcome(
                                    dispatched = true,
                                    completed = false,
                                    durationMs = SystemClock.uptimeMillis() - startedAt,
                                    detail = "$label cancelled by the system (another gesture or a scroll container intercepted it)"
                                )
                            )
                        }
                    }
                }

                val accepted = try {
                    service.dispatchGesture(gesture, callback, null)
                } catch (e: Exception) {
                    Log.e(TAG, "$label dispatch threw: ${e.message}")
                    false
                }

                if (!accepted && continuation.isActive) {
                    continuation.resume(
                        GestureOutcome(
                            dispatched = false,
                            completed = false,
                            durationMs = SystemClock.uptimeMillis() - startedAt,
                            detail = "$label rejected by the accessibility service"
                        )
                    )
                }
            }
        }

        if (outcome != null) return outcome

        // The callback never arrived. Report honestly instead of claiming success;
        // the caller's verification step decides whether anything actually changed.
        return GestureOutcome(
            dispatched = true,
            completed = false,
            durationMs = SystemClock.uptimeMillis() - startedAt,
            detail = "$label did not report completion within ${timeoutMs}ms"
        )
    }

    /**
     * True when the point lies inside a rectangle with a small inset, so taps do not
     * land exactly on a 1px border where the touch target may belong to the neighbour.
     */
    fun isTappablePoint(rect: Rect, x: Float, y: Float, inset: Int = 1): Boolean {
        if (rect.width() <= 0 || rect.height() <= 0) return false
        val safe = Rect(rect)
        if (safe.width() > inset * 2 + 2 && safe.height() > inset * 2 + 2) safe.inset(inset, inset)
        return safe.contains(x.toInt(), y.toInt())
    }
}
