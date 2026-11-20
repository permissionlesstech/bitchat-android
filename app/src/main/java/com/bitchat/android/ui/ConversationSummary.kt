package com.bitchat.android.ui

import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.model.DeliveryStatus
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
    val latestMessageIsOutgoing: Boolean = false,
    val latestDeliveryStatus: DeliveryStatus? = null,
    val transport: DirectMessageTransport,
    val nostrPubkey: String?,
    val identityAliases: Set<String>,
    val isConnected: Boolean = false,
    val connectedPeerID: String? = null,
    val sourceGeohash: String? = null,
    val isPinned: Boolean = false,
    val isMuted: Boolean = false,
    val draft: String? = null
)

internal fun buildConversationSummaries(
    unreadConversationIDs: Set<String>,
    privateChats: Map<String, List<BitchatMessage>>,
    currentUserIdentifiers: Set<String>,
    canonicalize: (String) -> String,
    isMessageRead: (BitchatMessage) -> Boolean,
    persistedUnreadCounts: Map<String, Int> = emptyMap()
): List<ConversationSummary> {
    if (privateChats.isEmpty()) return emptyList()

    val currentUsers = currentUserIdentifiers
        .filter(String::isNotBlank)
        .mapTo(mutableSetOf()) { it.lowercase() }
    val unreadCanonicalIDs = unreadConversationIDs
        .mapTo(mutableSetOf()) { canonicalize(it).lowercase() }
    val unreadCountsByCanonicalID = persistedUnreadCounts.entries
        .groupingBy { canonicalize(it.key).lowercase() }
        .fold(0) { total, entry -> total + entry.value }
    val aliasesByCanonicalID = linkedMapOf<String, MutableSet<String>>()
    val messagesByCanonicalID = linkedMapOf<String, MutableList<BitchatMessage>>()
    val displayCanonicalIDByNormalized = linkedMapOf<String, String>()

    privateChats.forEach { (sourceID, messages) ->
        val canonicalID = canonicalize(sourceID)
        val normalizedID = canonicalID.lowercase()
        displayCanonicalIDByNormalized.putIfAbsent(normalizedID, canonicalID)
        aliasesByCanonicalID
            .getOrPut(normalizedID) { linkedSetOf() }
            .add(sourceID)
        messagesByCanonicalID
            .getOrPut(normalizedID) { mutableListOf() }
            .addAll(messages)
    }

    return messagesByCanonicalID.mapNotNull { (normalizedConversationID, sourceMessages) ->
        val conversationID =
            displayCanonicalIDByNormalized.getValue(normalizedConversationID)
        val messages = sourceMessages.distinctBy { it.id }
        if (messages.isEmpty()) return@mapNotNull null

        fun activityOrder(message: BitchatMessage): Long =
            PrivateMessageArrivalOrder.sequenceOf(message.id) ?: message.timestamp.time

        val latest = messages.maxWithOrNull(
            compareBy<BitchatMessage>(::activityOrder).thenBy { it.id }
        ) ?: return@mapNotNull null
        fun isOutgoing(message: BitchatMessage): Boolean =
            message.sender.lowercase() in currentUsers ||
                message.senderPeerID?.lowercase() in currentUsers

        val incoming = messages.filterNot {
            isOutgoing(it) || it.sender == "system"
        }
        val latestIncoming = incoming.maxWithOrNull(
            compareBy<BitchatMessage>(::activityOrder).thenBy { it.id }
        )
        val canonicalUnread = conversationID.lowercase() in unreadCanonicalIDs
        val persistedUnreadCount = unreadCountsByCanonicalID[conversationID.lowercase()] ?: 0
        val unreadCount = maxOf(
            persistedUnreadCount,
            if (canonicalUnread && incoming.isNotEmpty()) {
                incoming.count { !isMessageRead(it) }.coerceAtLeast(1)
            } else {
                0
            }
        )
        val aliases = aliasesByCanonicalID[normalizedConversationID].orEmpty()
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
            latestMessageAt =
                PrivateMessageArrivalOrder.receivedAtOf(latest.id) ?: latest.timestamp.time,
            latestActivityOrder = activityOrder(latest),
            latestMessageType = latest.type,
            latestMessagePreview = latest.conversationPreview(),
            latestMessageIsOutgoing = isOutgoing(latest),
            latestDeliveryStatus = latest.deliveryStatus,
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

internal fun resolveConversationDisplayName(
    fallbackName: String,
    connectedPeerID: String?,
    peerNicknames: Map<String, String>,
    resolvedContactName: String?,
    persistedDisplayName: String?,
    geohashNicknames: Map<String, String> = emptyMap(),
    nostrPubkey: String? = null,
    identityAliases: Set<String> = emptySet()
): String {
    fun String?.usableName(): String? = this?.takeUnless {
        it.isBlank() || it.equals("Unknown", ignoreCase = true)
    }

    fun String?.announcedName(): String? = usableName()?.takeUnless {
        it.equals("anon", ignoreCase = true)
    }

    val liveName = connectedPeerID?.let { peerID ->
        peerNicknames[peerID]
            ?: peerNicknames.entries
                .firstOrNull { (candidateID, _) ->
                    candidateID.equals(peerID, ignoreCase = true)
                }
                ?.value
    }
    return liveName.usableName()
        ?: geohashNickname(nostrPubkey, identityAliases, geohashNicknames).announcedName()
        ?: resolvedContactName.usableName()
        ?: persistedDisplayName.usableName()
        ?: fallbackName
}

/**
 * Location-channel nicknames live on the Nostr pubkey, not on the last private message.
 * The open chat already reads that cache. The conversation row has to use it too, or a
 * rename that never sent a new DM stays stuck on the old sender name.
 */
private fun geohashNickname(
    nostrPubkey: String?,
    identityAliases: Set<String>,
    geohashNicknames: Map<String, String>
): String? {
    if (geohashNicknames.isEmpty()) return null
    val keys = buildList {
        nostrPubkey?.lowercase()?.let(::add)
        identityAliases.forEach { alias ->
            val normalized = alias.lowercase()
            if (normalized.startsWith("nostr_")) add(normalized.removePrefix("nostr_"))
        }
    }
    keys.forEach { key ->
        geohashNicknames[key]?.let { return it }
        if (key.length in 8..63) {
            geohashNicknames.entries
                .firstOrNull { (pubkey, _) -> pubkey.startsWith(key) }
                ?.value
                ?.let { return it }
        }
    }
    return null
}

internal fun sortConversationSummaries(
    conversations: List<ConversationSummary>
): List<ConversationSummary> = conversations.sortedWith(
    compareByDescending<ConversationSummary> { it.isConnected }
        .thenByDescending { it.isPinned }
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
