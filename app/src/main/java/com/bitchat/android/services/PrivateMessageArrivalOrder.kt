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
    private val receivedAtByMessageID = mutableMapOf<String, Long>()
    private var nextSequence = 0L

    fun record(messageID: String, receivedAt: Long = System.currentTimeMillis()) {
        synchronized(this) {
            if (messageID !in sequenceByMessageID) {
                sequenceByMessageID[messageID] = nextSequence++
                receivedAtByMessageID[messageID] = receivedAt
            }
        }
    }

    fun restore(
        persistedOrder: List<String>,
        liveMessageIDs: List<String>,
        persistedReceivedAt: Map<String, Long> = emptyMap(),
        persistedSequences: Map<String, Long> = emptyMap()
    ) {
        synchronized(this) {
            val previousSequences = sequenceByMessageID.toMap()
            val previousReceivedAt = receivedAtByMessageID.toMap()
            sequenceByMessageID.clear()
            receivedAtByMessageID.clear()
            nextSequence = (persistedSequences.values.maxOrNull() ?: -1L) + 1L
            (persistedOrder + liveMessageIDs).forEach { messageID ->
                if (messageID !in sequenceByMessageID) {
                    val sequence = persistedSequences[messageID]
                        ?: previousSequences[messageID]
                        ?: nextSequence++
                    sequenceByMessageID[messageID] = sequence
                    (persistedReceivedAt[messageID] ?: previousReceivedAt[messageID])?.let {
                        receivedAtByMessageID[messageID] = it
                    }
                }
            }
            nextSequence = maxOf(
                nextSequence,
                (sequenceByMessageID.values.maxOrNull() ?: -1L) + 1L
            )
        }
    }

    fun sequenceOf(messageID: String): Long? = synchronized(this) {
        sequenceByMessageID[messageID]
    }

    fun receivedAtOf(messageID: String): Long? = synchronized(this) {
        receivedAtByMessageID[messageID]
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
            receivedAtByMessageID.clear()
            nextSequence = 0L
        }
    }
}
