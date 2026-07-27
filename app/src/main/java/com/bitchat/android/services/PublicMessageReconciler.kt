package com.bitchat.android.services

import com.bitchat.android.model.BitchatMessage

/**
 * Owns public-timeline replay deduplication and bridge/radio reconciliation.
 *
 * The store remains responsible for synchronization and cross-timeline IDs;
 * this class keeps bridge-specific alias policy independently testable.
 */
internal class PublicMessageReconciler {
    data class Result(
        val messages: List<BitchatMessage>,
        val accepted: Boolean
    )

    private val seenKeys = mutableSetOf<String>()

    fun reconcile(
        existing: List<BitchatMessage>,
        incoming: BitchatMessage,
        messageIdAlreadySeen: Boolean
    ): Result {
        val withoutBridgeAliases = if (incoming.isBridged) {
            existing
        } else {
            existing.filterNot {
                it.isBridged && it.bridgeRadioMessageIdHint == incoming.id
            }
        }
        val key = publicMessageKey(incoming)
        if (messageIdAlreadySeen || key in seenKeys) {
            return Result(withoutBridgeAliases, accepted = false)
        }
        seenKeys += key
        return Result(withoutBridgeAliases + incoming, accepted = true)
    }

    fun clear() {
        seenKeys.clear()
    }

    private fun publicMessageKey(message: BitchatMessage): String {
        val sender = message.senderPeerID ?: message.sender
        return listOf(
            sender,
            message.timestamp.time.toString(),
            message.type.name,
            message.channel ?: "",
            message.content
        ).joinToString("\u001F")
    }
}
