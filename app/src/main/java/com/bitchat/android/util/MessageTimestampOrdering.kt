package com.bitchat.android.util

/**
 * Orders mesh and relay messages by their packet timestamp while clamping
 * excessive sender clock skew relative to the local receive time (issue #525).
 */
object MessageTimestampOrdering {

    /** Maximum tolerated clock skew (±5 minutes) before clamping to receive time. */
    const val MAX_CLOCK_SKEW_MS: Long = 5L * 60L * 1000L

    /**
     * Returns an effective ordering timestamp in epoch milliseconds:
     * - Uses [packetTimestampMs] when within ±[maxSkewMs] of [receivedAtMs].
     * - Clamps to `[receivedAtMs - maxSkewMs, receivedAtMs + maxSkewMs]` so a
     *   malicious or misconfigured peer clock cannot pin messages to the top or bottom.
     */
    fun effectiveTimestampMs(
        packetTimestampMs: Long,
        receivedAtMs: Long,
        maxSkewMs: Long = MAX_CLOCK_SKEW_MS
    ): Long {
        if (packetTimestampMs <= 0L) return receivedAtMs
        val lowerBound = receivedAtMs - maxSkewMs
        val upperBound = receivedAtMs + maxSkewMs
        return packetTimestampMs.coerceIn(lowerBound, upperBound)
    }

    /**
     * Deterministic comparator that orders by effective timestamp ascending,
     * breaking ties by message ID.
     */
    fun <T> comparator(
        packetTimestampMs: (T) -> Long,
        receivedAtMs: (T) -> Long,
        id: (T) -> String,
        maxSkewMs: Long = MAX_CLOCK_SKEW_MS
    ): Comparator<T> = Comparator { a, b ->
        val tsA = effectiveTimestampMs(packetTimestampMs(a), receivedAtMs(a), maxSkewMs)
        val tsB = effectiveTimestampMs(packetTimestampMs(b), receivedAtMs(b), maxSkewMs)
        val cmp = tsA.compareTo(tsB)
        if (cmp != 0) cmp else id(a).compareTo(id(b))
    }
}
