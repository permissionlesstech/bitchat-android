package com.bitchat.android.nostr

import com.bitchat.android.services.PrivateDeliveryJob

/** Bounded historical scans. Saturated ranges are subdivided; equal-second ties never skip. */
internal class NostrInboxSync(
    private val fetch: suspend (since: Int, until: Int, limit: Int) -> List<NostrEvent>?,
    private val process: suspend (NostrEvent) -> Boolean
) {
    companion object {
        const val WRAPPER_OVERLAP_MS = 172_800_000L + 900_000L
        fun since(now: Long, lastCompleted: Long?): Int =
            ((maxOf(now - PrivateDeliveryJob.RETENTION_MS, lastCompleted ?: Long.MIN_VALUE) - WRAPPER_OVERLAP_MS) / 1000).toInt()
    }

    suspend fun scan(since: Int, until: Int, limit: Int = 500): Boolean {
        val events = fetch(since, until, limit) ?: return false
        if (events.size >= limit) {
            if (since == until) {
                if (limit >= 16_000) return false // Report incomplete, never advance a truncated range.
                return scan(since, until, limit * 2)
            }
            val middle = since + (until - since) / 2
            // Process recent controls first; authenticated freshness rejects old replays.
            return scan(middle + 1, until) && scan(since, middle)
        }
        for (event in events) if (!process(event)) return false
        return true
    }
}
