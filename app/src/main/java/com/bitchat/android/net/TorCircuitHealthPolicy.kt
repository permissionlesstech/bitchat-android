package com.bitchat.android.net

import com.bitchat.android.util.AppConstants

/**
 * Decides when a Tor session that already finished bootstrapping should stop being reported as
 * connected.
 *
 * Arti's bootstrap milestones only fire on the way up. Once a guard is usable the session is
 * latched to 100%, and losing every exit circuit afterwards produces a stream of SOCKS errors
 * that no bootstrap milestone contradicts — so the indicator keeps claiming a working Tor.
 *
 * Circuit failures are a normal part of Tor operation, so a single burst must not move the
 * indicator. A downgrade requires [failureThreshold] failures that also span at least
 * [minSpanMs], which separates "three parallel attempts lost a flaky circuit" from "still
 * failing twenty seconds later". Progress on the wire clears the tally.
 *
 * Kept free of Android and coroutine dependencies so the policy is directly testable.
 */
internal class TorCircuitHealthPolicy(
    private val failureThreshold: Int = AppConstants.Tor.CIRCUIT_FAILURE_THRESHOLD,
    private val minSpanMs: Long = AppConstants.Tor.CIRCUIT_FAILURE_MIN_SPAN_MS,
    private val windowMs: Long = AppConstants.Tor.CIRCUIT_FAILURE_WINDOW_MS
) {
    private companion object {
        // Arti emits these once it cannot build or use an exit circuit. Matched as substrings in
        // the same style as the bootstrap milestones in ArtiTorManager.
        val FAILURE_MARKERS = listOf(
            "Failed to connect through Tor",
            "SOCKS connection error",
            "Failed to obtain exit circuit"
        )
    }

    private var failureCount = 0
    private var windowStartedAtMs = 0L

    fun isCircuitFailure(line: String): Boolean =
        FAILURE_MARKERS.any { line.contains(it, ignoreCase = true) }

    /**
     * Records one circuit failure at [nowMs] and reports whether the session has now failed for
     * long enough, and often enough, to stop being advertised as connected.
     */
    @Synchronized
    fun onCircuitFailure(nowMs: Long): Boolean {
        val elapsed = nowMs - windowStartedAtMs
        // A stale window, or a clock that moved backwards, starts counting again.
        if (failureCount == 0 || elapsed > windowMs || elapsed < 0L) {
            windowStartedAtMs = nowMs
            failureCount = 1
            return false
        }

        failureCount++
        return failureCount >= failureThreshold && elapsed >= minSpanMs
    }

    @Synchronized
    fun reset() {
        failureCount = 0
        windowStartedAtMs = 0L
    }
}
