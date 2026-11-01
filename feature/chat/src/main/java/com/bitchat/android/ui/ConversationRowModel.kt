package com.bitchat.android.ui

import com.bitchat.android.favorites.FavoriteRelationship
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.services.ContactIdentityResolver
import com.bitchat.android.services.ConversationStoreState

/*
 * The conversations and channels sections of the peer list as plain functions of snapshots plus
 * [PeerListLookups] (inventory P2, P3, P6, P7, P9, P10, P23, P24). Moved verbatim from the
 * composables. Rows carry precomputed values; the UI turns kinds into strings.
 */

/** The search field appears from this many conversations, counting all of them (Q-P7). */
internal const val CONVERSATION_SEARCH_THRESHOLD = 8

/** The status line under the conversations header, in precedence order (P9). */
internal enum class ConversationStatusKind { Loading, Error, Empty, NoResults }

/** How a connected peer is reached, for the transport badge and its description. */
internal enum class MeshConnectionKind { WifiAware, Bluetooth, Routed }

/** What the row says about where the peer is (P24 `presenceDescription` and badge). */
internal enum class ConversationPresence { Connected, ReachableViaNostr, NotInMesh }

/** The second line of a conversation row, before the "You: " prefix (P24 `basePreview`). */
internal sealed interface ConversationPreviewKind {
    /** "Draft: [text]". */
    data class Draft(val text: String) : ConversationPreviewKind
    /** The "sent an image" placeholder. */
    data object Image : ConversationPreviewKind
    /** The "sent a voice note" placeholder. */
    data object Voice : ConversationPreviewKind
    /** The "sent a file" placeholder, for a file with no name. */
    data object File : ConversationPreviewKind
    /** Shown as is: the message text ("…" when blank) or "📎 name" for a named file. */
    data class Literal(val text: String) : ConversationPreviewKind
}

internal data class ConversationRowUi(
    val conversationID: String,
    val displayName: String,
    /** [displayName] split for the avatar, name and suffix. */
    val baseName: String,
    val suffix: String,
    val unreadCount: Int,
    val isPinned: Boolean,
    val isMuted: Boolean,
    val isConnected: Boolean,
    val presence: ConversationPresence,
    val connection: MeshConnectionKind,
    val isWifiAware: Boolean,
    val isDirect: Boolean,
    val isFavorite: Boolean,
    val theyFavoritedUs: Boolean,
    val isVerified: Boolean,
    /** `PeerIdentity.stableKey` for `colorForPeer`. */
    val colorKey: String,
    val previewKind: ConversationPreviewKind,
    /** Prefix the preview with "You: ". */
    val previewIsOutgoing: Boolean,
    /** A non-blank draft: the preview is emphasised. */
    val hasDraft: Boolean,
    /** For the row's relative-time ticker. */
    val latestMessageAt: Long,
)

/** The favourite, they-favourited-us and verified badges of one conversation row (P23). */
internal data class ConversationFavoriteFlags(
    val isFavorite: Boolean,
    val theyFavoritedUs: Boolean,
    val isVerified: Boolean,
)

internal data class ConversationsUi(
    val showSearch: Boolean,
    val status: ConversationStatusKind?,
    val online: List<ConversationRowUi>,
    val offline: List<ConversationRowUi>,
)

internal data class ChannelRowUi(
    val name: String,
    val isSelected: Boolean,
    val unread: Int,
)

/** P2: peers with `peerDirect == true`, lowercased. */
internal fun directPeerIdentityIDs(peerDirect: Map<String, Boolean>): Set<String> =
    peerDirect
        .asSequence()
        .filter { (_, isDirect) -> isDirect }
        .mapTo(mutableSetOf()) { (peerID, _) -> peerID.lowercase() }

/** P3. */
internal fun wifiAwareIdentityIDs(wifiAwarePeerIDs: Set<String>): Set<String> =
    wifiAwarePeerIDs.mapTo(mutableSetOf()) { it.lowercase() }

/** P6: trimmed, case-insensitive match on name, latest preview or draft. */
internal fun filterConversations(
    conversations: List<ConversationSummary>,
    conversationQuery: String,
): List<ConversationSummary> {
    val query = conversationQuery.trim()
    return if (query.isEmpty()) conversations else conversations.filter { conversation ->
        conversation.displayName.contains(query, ignoreCase = true) ||
            conversation.latestMessagePreview.contains(query, ignoreCase = true) ||
            conversation.draft?.contains(query, ignoreCase = true) == true
    }
}

/** P7, online half; directory order is kept. */
internal fun onlineConversations(filtered: List<ConversationSummary>): List<ConversationSummary> =
    filtered.filter(ConversationSummary::isConnected)

/** P7, offline half. */
internal fun offlineConversations(filtered: List<ConversationSummary>): List<ConversationSummary> =
    filtered.filterNot(ConversationSummary::isConnected)

/** P9: counts all conversations, not the filtered ones (Q-P7). */
internal fun showConversationSearch(conversations: List<ConversationSummary>): Boolean =
    conversations.size >= CONVERSATION_SEARCH_THRESHOLD

/** P9: Loading and Error only while there are no conversations; then empty; then no results. */
internal fun conversationSectionStatus(
    conversationStoreState: ConversationStoreState,
    conversations: List<ConversationSummary>,
    filteredConversations: List<ConversationSummary>,
): ConversationStatusKind? = when {
    conversationStoreState is ConversationStoreState.Loading &&
        conversations.isEmpty() -> ConversationStatusKind.Loading

    conversationStoreState is ConversationStoreState.Error &&
        conversations.isEmpty() -> ConversationStatusKind.Error

    conversations.isEmpty() -> ConversationStatusKind.Empty

    filteredConversations.isEmpty() -> ConversationStatusKind.NoResults

    else -> null
}

/** P10: set iteration order; the badge shows only when the channel is not selected (UI). */
internal fun buildChannelRows(
    joinedChannels: Set<String>,
    currentChannel: String?,
    unreadChannelMessages: Map<String, Int>,
): List<ChannelRowUi> = joinedChannels.toList().map { channel ->
    ChannelRowUi(
        name = channel,
        isSelected = channel == currentChannel,
        unread = unreadChannelMessages[channel] ?: 0,
    )
}

/** P23, first part: the first alias with a stored favourite relationship. */
internal fun conversationFavoriteRelationship(
    identityAliases: Set<String>,
    lookups: PeerListLookups,
): FavoriteRelationship? =
    identityAliases
        .asSequence()
        .mapNotNull { alias ->
            runCatching {
                lookups.favoriteStatus(alias)
            }.getOrNull()
        }
        .firstOrNull()

/**
 * P23: fingerprint from the connected peer, then any alias, then a `contact_` conversation ID,
 * then the relationship's Noise key; badges from that fingerprint.
 */
internal fun conversationFavoriteFlags(
    conversation: ConversationSummary,
    favoriteRelationship: FavoriteRelationship?,
    favoritePeers: Set<String>,
    peerFavoritedUs: Set<String>,
    peerFingerprints: Map<String, String>,
    verifiedFingerprints: Set<String>,
    lookups: PeerListLookups,
): ConversationFavoriteFlags {
    val favoriteTargetID = conversation.connectedPeerID ?: conversation.conversationID
    val fingerprint = conversation.connectedPeerID
        ?.let(peerFingerprints::get)
        ?: conversation.identityAliases
            .asSequence()
            .mapNotNull(peerFingerprints::get)
            .firstOrNull()
        ?: ContactIdentityResolver.fingerprintFromContactConversationId(
            conversation.conversationID
        )
        ?: favoriteRelationship?.peerNoisePublicKey?.let {
            ContactIdentityResolver.fingerprintHex(it)
        }
    val isFavorite = if (fingerprint != null) {
        fingerprint in favoritePeers
    } else {
        lookups.isFavorite(favoriteTargetID)
    }
    val theyFavoritedUs =
        (fingerprint != null && fingerprint in peerFavoritedUs) ||
            favoriteRelationship?.theyFavoritedUs == true
    val isVerified = fingerprint != null && fingerprint in verifiedFingerprints
    return ConversationFavoriteFlags(
        isFavorite = isFavorite,
        theyFavoritedUs = theyFavoritedUs,
        isVerified = isVerified,
    )
}

/** WifiAware, then Bluetooth direct, then routed. */
internal fun meshConnectionKind(isWifiAware: Boolean, isDirect: Boolean): MeshConnectionKind =
    when {
        isWifiAware -> MeshConnectionKind.WifiAware
        isDirect -> MeshConnectionKind.Bluetooth
        else -> MeshConnectionKind.Routed
    }

/** P24: one conversation row, given its P23 badges. */
internal fun buildConversationRow(
    conversation: ConversationSummary,
    favoriteFlags: ConversationFavoriteFlags,
    directPeerIdentityIDs: Set<String>,
    wifiAwareIdentityIDs: Set<String>,
    lookups: PeerListLookups,
): ConversationRowUi {
    val liveIdentityIDs = conversation.identityAliases +
        listOfNotNull(conversation.connectedPeerID?.lowercase())
    val isWifiAware = liveIdentityIDs.any(wifiAwareIdentityIDs::contains)
    val isDirect = liveIdentityIDs.any(directPeerIdentityIDs::contains) ||
        conversation.connectedPeerID?.let { peerID ->
            runCatching {
                lookups.peerInfo(peerID)?.isDirectConnection == true
            }.getOrDefault(false)
        } == true
    // A local, because a property from another module cannot be smart-cast.
    val draft = conversation.draft
    val previewKind = when {
        !draft.isNullOrBlank() -> ConversationPreviewKind.Draft(draft)
        conversation.latestMessageType == BitchatMessageType.Image ->
            ConversationPreviewKind.Image
        conversation.latestMessageType == BitchatMessageType.Audio ->
            ConversationPreviewKind.Voice
        conversation.latestMessageType == BitchatMessageType.File ->
            conversation.latestMessagePreview
                .takeIf(String::isNotBlank)
                ?.let { ConversationPreviewKind.Literal("📎 $it") }
                ?: ConversationPreviewKind.File
        else -> ConversationPreviewKind.Literal(conversation.latestMessagePreview.ifBlank { "…" })
    }
    val presence = when {
        conversation.isConnected -> ConversationPresence.Connected
        conversation.transport == DirectMessageTransport.NOSTR ->
            ConversationPresence.ReachableViaNostr
        else -> ConversationPresence.NotInMesh
    }
    val peerIdentity = conversation.nostrPubkey
        ?.let(PeerIdentity::nostr)
        ?: PeerIdentity.mesh(conversation.conversationID)
    val (baseNameRaw, suffix) = splitSuffix(conversation.displayName)
    return ConversationRowUi(
        conversationID = conversation.conversationID,
        displayName = conversation.displayName,
        baseName = baseNameRaw,
        suffix = suffix,
        unreadCount = conversation.unreadCount,
        isPinned = conversation.isPinned,
        isMuted = conversation.isMuted,
        isConnected = conversation.isConnected,
        presence = presence,
        connection = meshConnectionKind(isWifiAware = isWifiAware, isDirect = isDirect),
        isWifiAware = isWifiAware,
        isDirect = isDirect,
        isFavorite = favoriteFlags.isFavorite,
        theyFavoritedUs = favoriteFlags.theyFavoritedUs,
        isVerified = favoriteFlags.isVerified,
        colorKey = peerIdentity.stableKey,
        previewKind = previewKind,
        previewIsOutgoing =
            conversation.latestMessageIsOutgoing && conversation.draft.isNullOrBlank(),
        hasDraft = !conversation.draft.isNullOrBlank(),
        latestMessageAt = conversation.latestMessageAt,
    )
}

/** The whole conversations section: P2/P3, P6, P7, P9, P23 and P24 together. */
internal fun buildConversationRows(
    conversations: List<ConversationSummary>,
    query: String,
    conversationStoreState: ConversationStoreState,
    favoritePeers: Set<String>,
    peerFavoritedUs: Set<String>,
    peerFingerprints: Map<String, String>,
    verifiedFingerprints: Set<String>,
    peerDirect: Map<String, Boolean>,
    wifiAwarePeerIDs: Set<String>,
    lookups: PeerListLookups,
): ConversationsUi {
    val directIDs = directPeerIdentityIDs(peerDirect)
    val wifiAwareIDs = wifiAwareIdentityIDs(wifiAwarePeerIDs)
    val filtered = filterConversations(conversations, query)
    fun row(conversation: ConversationSummary): ConversationRowUi = buildConversationRow(
        conversation = conversation,
        favoriteFlags = conversationFavoriteFlags(
            conversation = conversation,
            favoriteRelationship =
                conversationFavoriteRelationship(conversation.identityAliases, lookups),
            favoritePeers = favoritePeers,
            peerFavoritedUs = peerFavoritedUs,
            peerFingerprints = peerFingerprints,
            verifiedFingerprints = verifiedFingerprints,
            lookups = lookups,
        ),
        directPeerIdentityIDs = directIDs,
        wifiAwareIdentityIDs = wifiAwareIDs,
        lookups = lookups,
    )
    return ConversationsUi(
        showSearch = showConversationSearch(conversations),
        status = conversationSectionStatus(conversationStoreState, conversations, filtered),
        online = onlineConversations(filtered).map(::row),
        offline = offlineConversations(filtered).map(::row),
    )
}
