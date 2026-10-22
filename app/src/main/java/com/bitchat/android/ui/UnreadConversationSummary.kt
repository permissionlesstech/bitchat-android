package com.bitchat.android.ui

import com.bitchat.android.model.BitchatMessage

internal enum class DirectMessageTransport {
    MESH,
    NOSTR
}

/**
 * Presence-independent presentation state for an unread private conversation.
 *
 * A conversation remains in this model until it is read, even when none of its identities are in
 * the current mesh or geohash participant lists.
 */
internal data class UnreadConversationSummary(
    val conversationID: String,
    val displayName: String,
    val unreadCount: Int,
    val latestMessageAt: Long,
    val transport: DirectMessageTransport,
    val nostrPubkey: String?,
    val identityAliases: Set<String>,
    val isConnected: Boolean = false,
    val sourceGeohash: String? = null
)

internal fun buildUnreadConversationSummaries(
    unreadConversationIDs: Set<String>,
    privateChats: Map<String, List<BitchatMessage>>,
    currentUserIdentifiers: Set<String>,
    canonicalize: (String) -> String,
    isMessageRead: (BitchatMessage) -> Boolean
): List<UnreadConversationSummary> {
    if (unreadConversationIDs.isEmpty()) return emptyList()

    val normalizedCurrentUserIdentifiers = currentUserIdentifiers.filterTo(mutableSetOf()) {
        it.isNotBlank()
    }
    val canonicalUnreadIDs = unreadConversationIDs
        .mapTo(linkedSetOf()) { canonicalize(it) }
    val unreadAliasesByCanonicalID = unreadConversationIDs.groupBy(canonicalize)
    val messagesByCanonicalID = linkedMapOf<String, MutableList<BitchatMessage>>()

    privateChats.forEach { (conversationID, messages) ->
        val canonicalID = canonicalize(conversationID)
        messagesByCanonicalID.getOrPut(canonicalID) { mutableListOf() }.addAll(messages)
    }

    return canonicalUnreadIDs.map { conversationID ->
        val messages = messagesByCanonicalID[conversationID]
            .orEmpty()
            .distinctBy { it.id }
        val incomingMessages = messages.filterNot {
            it.sender in normalizedCurrentUserIdentifiers
        }
        val unreadIncomingMessages = incomingMessages.filterNot(isMessageRead)
        val latestMessage = (unreadIncomingMessages.ifEmpty { incomingMessages })
            .maxWithOrNull(compareBy<BitchatMessage> { it.timestamp.time }.thenBy { it.id })
        val aliases = unreadAliasesByCanonicalID[conversationID].orEmpty()
        val nostrPubkey = latestMessage?.senderNostrPubkey
        val isNostrConversation = nostrPubkey != null ||
            aliases.any(::isNostrConversationID) ||
            isNostrConversationID(conversationID)

        UnreadConversationSummary(
            conversationID = conversationID,
            displayName = latestMessage
                ?.sender
                ?.takeIf { it.isNotBlank() }
                ?: conversationID.take(12),
            unreadCount = unreadIncomingMessages.size.coerceAtLeast(1),
            latestMessageAt = latestMessage?.timestamp?.time ?: Long.MIN_VALUE,
            transport = if (isNostrConversation) {
                DirectMessageTransport.NOSTR
            } else {
                DirectMessageTransport.MESH
            },
            nostrPubkey = nostrPubkey,
            identityAliases = (aliases + conversationID)
                .mapTo(mutableSetOf()) { it.lowercase() }
        )
    }.sortedWith(
        compareByDescending<UnreadConversationSummary> { it.latestMessageAt }
            .thenBy { it.displayName.lowercase() }
            .thenBy { it.conversationID }
    )
}

private fun isNostrConversationID(value: String): Boolean =
    value.startsWith("nostr_") || value.startsWith("nostr:")

internal fun matchingUnreadAliases(
    unreadConversationIDs: Set<String>,
    canonicalConversationID: String,
    canonicalize: (String) -> String
): Set<String> {
    val normalizedCanonicalID = canonicalConversationID.lowercase()
    return unreadConversationIDs
        .filterTo(mutableSetOf()) { unreadID ->
            canonicalize(unreadID).equals(normalizedCanonicalID, ignoreCase = true)
        }
        .plus(canonicalConversationID)
        .mapTo(mutableSetOf()) { it.lowercase() }
}
