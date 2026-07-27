package com.bitchat.android.services

import com.bitchat.android.model.BitchatMessage

/**
 * Process-local receipt sequence for private messages.
 *
 * Peer-provided timestamps cannot safely order a conversation because clocks can differ. This
 * registry follows the lifetime of [AppStateStore] and lets alias merges reconstruct the order in
 * which messages entered the app, even when they were initially stored under different keys.
 */
internal object PrivateMessageArrivalOrder {
    private val sequenceByMessageID = mutableMapOf<String, Long>()
    private var nextSequence = 0L

    fun record(messageID: String) {
        synchronized(this) {
            if (messageID !in sequenceByMessageID) {
                sequenceByMessageID[messageID] = nextSequence++
            }
        }
    }

    fun order(messages: List<BitchatMessage>): List<BitchatMessage> {
        synchronized(this) {
            if (messages.size < 2 || messages.any { it.id !in sequenceByMessageID }) {
                return messages
            }
            return messages.sortedBy { sequenceByMessageID.getValue(it.id) }
        }
    }

    fun clear() {
        synchronized(this) {
            sequenceByMessageID.clear()
            nextSequence = 0L
        }
    }
}
