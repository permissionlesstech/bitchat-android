package com.bitchat.android.util

import com.bitchat.android.model.BitchatMessage

/**
 * Ordering helpers for chat timelines.
 *
 * Incoming mesh messages can arrive out of order. When a peer store-forwards or gossip-syncs an
 * older backlog after reconnecting, hour-old messages show up after current ones if we simply
 * append in receive order. [BitchatMessage.timestamp] carries the source packet time, so inserting
 * each message at its timestamp position keeps the visible timeline chronological.
 */
object MessageOrdering {

    /**
     * Insert [message] into [messages] so the list stays sorted by [BitchatMessage.timestamp].
     *
     * Assumes [messages] is already timestamp-ordered (the timelines it is used on are always built
     * through this path), so it can binary-search the insertion point and stay cheap on long lists.
     * Ordering is stable: a message whose timestamp equals existing ones is placed after them, so
     * equal timestamps keep insertion order.
     *
     * In-order arrival is overwhelmingly the common case, so a message at or after the current tail
     * is appended in O(1) and skips the search; only an out-of-order message (older than the tail —
     * e.g. a store-forwarded backlog) pays for the binary search. Mirrors iOS
     * `ConversationStore.insert(_:)`.
     */
    fun insertByTimestamp(messages: MutableList<BitchatMessage>, message: BitchatMessage) {
        val ts = message.timestamp.time
        val last = messages.lastOrNull()
        if (last == null || ts >= last.timestamp.time) {
            messages.add(message)
            return
        }
        var lo = 0
        var hi = messages.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (messages[mid].timestamp.time <= ts) lo = mid + 1 else hi = mid
        }
        messages.add(lo, message)
    }

    /**
     * Return a new list containing [messages] plus [message], kept sorted by timestamp (stable).
     */
    fun withMessageInserted(messages: List<BitchatMessage>, message: BitchatMessage): List<BitchatMessage> {
        val result = messages.toMutableList()
        insertByTimestamp(result, message)
        return result
    }
}
