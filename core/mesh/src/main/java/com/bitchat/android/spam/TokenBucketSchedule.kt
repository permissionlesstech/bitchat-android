package com.bitchat.android.spam

/**
 * A set of token buckets for one sender, evaluated on message time instead of arrival order.
 *
 * Relay backfill and mesh sync hand over a sender's recent history in one burst, often newest
 * first, so a plain arrival-time bucket would hide an ordinary conversation. Instead each
 * admitted message stays on a timeline, and a candidate is admitted only if replaying every
 * bucket over the timeline with the candidate inserted never runs dry. For live, in-order
 * traffic this behaves exactly like a classic token bucket. Rejected messages cost nothing.
 *
 * Entries older than the backdate window are folded into a checkpoint, and nothing can be
 * inserted before the checkpoint, so the replay stays short and exact.
 */
internal class TokenBucketSchedule(private val buckets: List<TokenBucketConfig>) {

    private class Entry(val timeMs: Long, val costs: DoubleArray)

    private val entries = ArrayList<Entry>()
    private var checkpointMs: Long? = null
    private val checkpointTokens = DoubleArray(buckets.size) { buckets[it].capacity }

    /**
     * Admits and records the message when every bucket can pay [costs] at [timeMs].
     * Returns -1 on success, otherwise the index of the first bucket that ran dry.
     */
    fun tryAdmit(timeMs: Long, costs: DoubleArray): Int {
        require(costs.size == buckets.size)
        val time = checkpointMs?.let { maxOf(it, timeMs) } ?: timeMs
        var insertAt = entries.size
        while (insertAt > 0 && entries[insertAt - 1].timeMs > time) insertAt--
        val candidate = Entry(time, costs)

        val tokens = checkpointTokens.copyOf()
        var lastMs = checkpointMs
        for (i in 0..entries.size) {
            val entry = when {
                i < insertAt -> entries[i]
                i == insertAt -> candidate
                else -> entries[i - 1]
            }
            refill(tokens, lastMs, entry.timeMs)
            lastMs = entry.timeMs
            for (b in buckets.indices) {
                tokens[b] -= entry.costs[b]
                if (tokens[b] < -EPSILON) return b
            }
        }
        entries.add(insertAt, candidate)
        return -1
    }

    /** Folds entries before [cutoffMs] into the checkpoint. */
    fun prune(cutoffMs: Long) {
        var folded = 0
        while (folded < entries.size && entries[folded].timeMs < cutoffMs) {
            val entry = entries[folded]
            refill(checkpointTokens, checkpointMs, entry.timeMs)
            for (b in buckets.indices) checkpointTokens[b] -= entry.costs[b]
            checkpointMs = entry.timeMs
            folded++
        }
        if (folded > 0) entries.subList(0, folded).clear()
    }

    private fun refill(tokens: DoubleArray, fromMs: Long?, toMs: Long) {
        if (fromMs == null || toMs <= fromMs) return
        val seconds = (toMs - fromMs) / 1000.0
        for (b in buckets.indices) {
            tokens[b] = minOf(buckets[b].capacity, tokens[b] + seconds * buckets[b].refillPerSecond)
        }
    }

    private companion object {
        const val EPSILON = 1e-9
    }
}
