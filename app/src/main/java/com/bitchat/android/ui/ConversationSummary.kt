package com.bitchat.android.ui

import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.services.PrivateMessageArrivalOrder

/**
 * Presence-independent presentation state for every retained private conversation.
 */
internal data class ConversationSummary(
    val conversationID: String,
    val displayName: String,
    val unreadCount: Int,
    val latestMessageAt: Long,
    val latestActivityOrder: Long,
    val latestMessageType: BitchatMessageType,
    val latestMessagePreview: String,
    val transport: DirectMessageTransport,
    val nostrPubkey: String?,
    val identityAliases: Set<String>,
    val isConnected: Boolean = false,
    val connectedPeerID: String? = null,
    val sourceGeohash: String? = null
)

internal fun buildConversationSummaries(
    unreadConversationIDs: Set<String>,
    privateChats: Map<String, List<BitchatMessage>>,
    currentUserIdentifiers: Set<String>,
    canonicalize: (String) -> String,
    isMessageRead: (BitchatMessage) -> Boolean
): List<ConversationSummary> {
    if (privateChats.isEmpty()) return emptyList()

    val currentUsers = currentUserIdentifiers.filterTo(mutableSetOf()) { it.isNotBlank() }
    val unreadCanonicalIDs = unreadConversationIDs
        .mapTo(mutableSetOf()) { canonicalize(it).lowercase() }
    val aliasesByCanonicalID = linkedMapOf<String, MutableSet<String>>()
    val messagesByCanonicalID = linkedMapOf<String, MutableList<BitchatMessage>>()

    privateChats.forEach { (sourceID, messages) ->
        val canonicalID = canonicalize(sourceID)
        aliasesByCanonicalID
            .getOrPut(canonicalID) { linkedSetOf() }
            .add(sourceID)
        messagesByCanonicalID
            .getOrPut(canonicalID) { mutableListOf() }
            .addAll(messages)
    }

    return messagesByCanonicalID.mapNotNull { (conversationID, sourceMessages) ->
        val messages = sourceMessages.distinctBy { it.id }
        if (messages.isEmpty()) return@mapNotNull null

        fun activityOrder(message: BitchatMessage): Long =
            PrivateMessageArrivalOrder.sequenceOf(message.id) ?: message.timestamp.time

        val latest = messages.maxWithOrNull(
            compareBy<BitchatMessage>(::activityOrder).thenBy { it.id }
        ) ?: return@mapNotNull null
        val incoming = messages.filterNot {
            it.sender in currentUsers || it.sender == "system"
        }
        val latestIncoming = incoming.maxWithOrNull(
            compareBy<BitchatMessage>(::activityOrder).thenBy { it.id }
        )
        val canonicalUnread = conversationID.lowercase() in unreadCanonicalIDs
        val unreadCount = if (canonicalUnread) {
            incoming.count { !isMessageRead(it) }.coerceAtLeast(1)
        } else {
            0
        }
        val aliases = aliasesByCanonicalID[conversationID].orEmpty()
        val nostrPubkey = latestIncoming?.senderNostrPubkey ?: latest.senderNostrPubkey
        val isNostrConversation = nostrPubkey != null ||
            aliases.any(::isNostrConversationKey) ||
            isNostrConversationKey(conversationID)
        val displayName = latestIncoming
            ?.sender
            ?.takeIf { it.isNotBlank() }
            ?: latest.recipientNickname
                ?.takeIf { it.isNotBlank() }
            ?: latest.sender.takeIf {
                it.isNotBlank() && it !in currentUsers && it != "system"
            }
            ?: conversationID.take(12)

        ConversationSummary(
            conversationID = conversationID,
            displayName = displayName,
            unreadCount = unreadCount,
            latestMessageAt = latest.timestamp.time,
            latestActivityOrder = activityOrder(latest),
            latestMessageType = latest.type,
            latestMessagePreview = latest.conversationPreview(),
            transport = if (isNostrConversation) {
                DirectMessageTransport.NOSTR
            } else {
                DirectMessageTransport.MESH
            },
            nostrPubkey = nostrPubkey,
            identityAliases = (aliases + conversationID)
                .mapTo(mutableSetOf()) { it.lowercase() }
        )
    }
}

internal fun sortConversationSummaries(
    conversations: List<ConversationSummary>
): List<ConversationSummary> = conversations.sortedWith(
    compareByDescending<ConversationSummary> { it.isConnected }
        .thenByDescending { it.unreadCount > 0 }
        .thenByDescending { it.latestActivityOrder }
        .thenBy { it.displayName.lowercase() }
        .thenBy { it.conversationID }
)

private fun isNostrConversationKey(value: String): Boolean =
    value.startsWith("nostr_") || value.startsWith("nostr:")

private fun BitchatMessage.conversationPreview(): String {
    val preview = when (type) {
        BitchatMessageType.File -> content
            .substringAfterLast('/')
            .substringAfterLast('\\')
        else -> content
    }
    return preview
        .replace(CONVERSATION_PREVIEW_WHITESPACE, " ")
        .trim()
        .take(MAX_CONVERSATION_PREVIEW_LENGTH)
}

private val CONVERSATION_PREVIEW_WHITESPACE = Regex("\\s+")
private const val MAX_CONVERSATION_PREVIEW_LENGTH = 240
