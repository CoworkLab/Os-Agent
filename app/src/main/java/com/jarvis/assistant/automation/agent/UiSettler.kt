package com.jarvis.assistant.automation.agent

import android.util.Log
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import kotlinx.coroutines.delay

/**
 * Waits for the screen to stop changing before the agent reads or touches it.
 *
 * Acting on a UI mid-animation is a primary cause of taps landing on the wrong
 * target and of text being typed into a field that has not taken focus yet. Fixed
 * sleeps are both too slow and too short; this polls the real tree instead and
 * returns as soon as it stabilises.
 */
object UiSettler {

    private const val TAG = "UiSettler"

    data class SettleResult(
        val stable: Boolean,
        val waitedMs: Long,
        val polls: Int,
        val signature: ScreenSignature?
    )

    /**
     * Polls the active window until two consecutive reads produce the same screen
     * signature, or until [maxWaitMs] elapses.
     *
     * @param quietMs how long the screen must remain unchanged to count as settled
     */
    suspend fun awaitStable(
        service: JarvisAccessibilityService,
        maxWaitMs: Long = 2_500L,
        pollIntervalMs: Long = 90L,
        quietMs: Long = 180L,
        requiredStablePolls: Int = 2
    ): SettleResult {
        val start = android.os.SystemClock.uptimeMillis()
        var polls = 0
        var previous: ScreenSignature? = null
        var stableCount = 0
        var lastChangeAt = start

        while (android.os.SystemClock.uptimeMillis() - start < maxWaitMs) {
            val root = service.getActiveRootNode() ?: service.getAppRootNode()
            val signature = ScreenSignatureCalculator.computeSignature(root)
            polls++

            if (previous != null && previous.isEquivalentTo(signature)) {
                stableCount++
                if (stableCount >= requiredStablePolls &&
                    android.os.SystemClock.uptimeMillis() - lastChangeAt >= quietMs
                ) {
                    return SettleResult(
                        stable = true,
                        waitedMs = android.os.SystemClock.uptimeMillis() - start,
                        polls = polls,
                        signature = signature
                    )
                }
            } else {
                stableCount = 0
                lastChangeAt = android.os.SystemClock.uptimeMillis()
            }

            previous = signature
            delay(pollIntervalMs)
        }

        Log.w(TAG, "Screen did not settle within ${maxWaitMs}ms (polls=$polls); proceeding with a best-effort read")
        return SettleResult(
            stable = false,
            waitedMs = android.os.SystemClock.uptimeMillis() - start,
            polls = polls,
            signature = previous
        )
    }

    /**
     * Waits for the screen to differ from [before]. Used right after an action so the
     * agent can tell "nothing happened" from "the UI is still animating".
     */
    suspend fun awaitChange(
        service: JarvisAccessibilityService,
        before: ScreenSignature?,
        maxWaitMs: Long = 2_000L,
        pollIntervalMs: Long = 80L
    ): ScreenSignature? {
        if (before == null) {
            delay(150)
            return ScreenSignatureCalculator.computeSignature(service.getActiveRootNode())
        }
        val start = android.os.SystemClock.uptimeMillis()
        while (android.os.SystemClock.uptimeMillis() - start < maxWaitMs) {
            val current = ScreenSignatureCalculator.computeSignature(
                service.getActiveRootNode() ?: service.getAppRootNode()
            )
            if (!current.isEquivalentTo(before)) return current
            delay(pollIntervalMs)
        }
        return null
    }
}
