package com.bitchat.android.ui

import com.bitchat.android.favorites.FavoriteRelationship
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.services.ContactIdentityResolver
import com.bitchat.android.util.hexEncodedString

/*
 * The mesh "People" card of the peer list as plain functions of snapshots plus
 * [PeerListLookups] (inventory P1-P5, P8, P12-P22, P25). Moved verbatim from the composables,
 * quirks included; the quirk IDs mark the lines tests pin.
 */

/** One row of the mesh people card: a connected peer or an offline favourite. */
internal data class MeshPersonRowUi(
    /** The animated list key: the peer ID, or the Noise key hex of an offline favourite. */
    val key: String,
    /** Identity for colour and the "me" check: the peer ID, or the favourite's Noise key hex. */
    val peerID: String,
    val displayName: String,
    /** [displayName] before any `#abcd` suffix. */
    val baseName: String,
    /** The `#abcd` suffix to show, or "" when [showHashSuffix] is false or there is none. */
    val suffix: String,
    val showHashSuffix: Boolean,
    val isMe: Boolean,
    /** `PeerIdentity.stableKey` for `colorForPeer`. */
    val colorKey: String,
    val isDirect: Boolean,
    val isWifiAware: Boolean,
    val isConnected: Boolean,
    val isSelected: Boolean,
    val isFavorite: Boolean,
    val theyFavoritedUs: Boolean,
    val isVerified: Boolean,
    val unreadCount: Int,
    val showNostrGlobe: Boolean,
    /** What a tap opens. */
    val openPeerID: String,
)

/** P1. */
internal fun wifiAwarePeerIDs(wifiAwareConnected: Map<String, *>): Set<String> =
    wifiAwareConnected.keys.toSet()

/** P4: every conversation alias, already lowercased by `buildConversationSummaries`. */
internal fun conversationIdentityAliases(conversations: List<ConversationSummary>): Set<String> =
    conversations
        .flatMapTo(mutableSetOf()) { it.identityAliases }

/** P5: connected peers that do not already show as a conversation. */
internal fun visibleConnectedPeers(
    connectedPeers: List<String>,
    conversationIdentityAliases: Set<String>,
    lookups: PeerListLookups,
): List<String> = connectedPeers.filterNot { peerID ->
    val aliases = runCatching {
        lookups.aliasesForConversation(peerID)
    }.getOrDefault(setOf(peerID))
    aliases.any { it.lowercase() in conversationIdentityAliases }
}

/**
 * P8, mesh branch: the people-card header count. Excludes self and every peer P5 hid, and does
 * not count offline favourites (Q-P2).
 */
internal fun meshPeopleCount(visibleConnectedPeers: List<String>, myPeerID: String): Int =
    visibleConnectedPeers.count { it != myPeerID }

/** P12. */
internal fun peerFavoriteStates(
    connectedPeers: List<String>,
    favoritePeers: Set<String>,
    peerFingerprints: Map<String, String>,
    lookups: PeerListLookups,
): Map<String, Boolean> = connectedPeers.associateWith { peerID ->
    val fingerprint = peerFingerprints[peerID]
    if (fingerprint != null) favoritePeers.contains(fingerprint) else lookups.isFavorite(peerID)
}

/** P13: the same "they favourited us" signal the private-chat header uses. */
internal fun peerTheyFavoritedUsStates(
    connectedPeers: List<String>,
    peerFavoritedUs: Set<String>,
    peerFingerprints: Map<String, String>,
    lookups: PeerListLookups,
): Map<String, Boolean> = connectedPeers.associateWith { peerID ->
    val fingerprint = peerFingerprints[peerID]
    if (fingerprint != null && peerFavoritedUs.contains(fingerprint)) {
        true
    } else {
        try {
            lookups.favoriteStatus(peerID)?.theyFavoritedUs == true
        } catch (_: Exception) {
            false
        }
    }
}

/** P14. */
internal fun peerVerifiedStates(
    connectedPeers: List<String>,
    verifiedFingerprints: Set<String>,
    lookups: PeerListLookups,
): Map<String, Boolean> = connectedPeers.associateWith { peerID ->
    isPeerVerified(peerID, verifiedFingerprints, lookups)
}

/** P14 for one peer: its display fingerprint is verified; never true for Nostr aliases. */
internal fun isPeerVerified(
    peerID: String,
    verifiedFingerprints: Set<String>,
    lookups: PeerListLookups,
): Boolean {
    if (peerID.startsWith("nostr_") || peerID.startsWith("nostr:")) return false
    val fingerprint = lookups.peerFingerprintForDisplay(peerID)
    return fingerprint != null && verifiedFingerprints.contains(fingerprint)
}

/**
 * Every row of the mesh people card, in display order: [connectedPeers] (already filtered by
 * P5) sorted, then offline favourites. Computes P12-P14 from the snapshots.
 */
internal fun buildMeshPeopleRows(
    connectedPeers: List<String>,
    peerNicknames: Map<String, String>,
    peerDirect: Map<String, Boolean>,
    wifiAwarePeerIDs: Set<String>,
    favoritePeers: Set<String>,
    peerFavoritedUs: Set<String>,
    peerFingerprints: Map<String, String>,
    verifiedFingerprints: Set<String>,
    unreadPrivateMessages: Set<String>,
    privateChats: Map<String, List<BitchatMessage>>,
    selectedPrivatePeer: String?,
    nickname: String,
    excludedIdentityAliases: Set<String>,
    lookups: PeerListLookups,
): List<MeshPersonRowUi> = meshPeopleRows(
    connectedPeers = connectedPeers,
    peerNicknames = peerNicknames,
    peerDirect = peerDirect,
    wifiAwarePeerIDs = wifiAwarePeerIDs,
    verifiedFingerprints = verifiedFingerprints,
    unreadPrivateMessages = unreadPrivateMessages,
    privateChats = privateChats,
    selectedPrivatePeer = selectedPrivatePeer,
    nickname = nickname,
    excludedIdentityAliases = excludedIdentityAliases,
    peerFavoriteStates = peerFavoriteStates(connectedPeers, favoritePeers, peerFingerprints, lookups),
    peerTheyFavoritedUsStates =
        peerTheyFavoritedUsStates(connectedPeers, peerFavoritedUs, peerFingerprints, lookups),
    peerVerifiedStates = peerVerifiedStates(connectedPeers, verifiedFingerprints, lookups),
    lookups = lookups,
)

/**
 * P15-P22 and P25, given P12-P14 (which the composable memoises on their own keys today).
 */
internal fun meshPeopleRows(
    connectedPeers: List<String>,
    peerNicknames: Map<String, String>,
    peerDirect: Map<String, Boolean>,
    wifiAwarePeerIDs: Set<String>,
    verifiedFingerprints: Set<String>,
    unreadPrivateMessages: Set<String>,
    privateChats: Map<String, List<BitchatMessage>>,
    selectedPrivatePeer: String?,
    nickname: String,
    excludedIdentityAliases: Set<String>,
    peerFavoriteStates: Map<String, Boolean>,
    peerTheyFavoritedUsStates: Map<String, Boolean>,
    peerVerifiedStates: Map<String, Boolean>,
    lookups: PeerListLookups,
): List<MeshPersonRowUi> {
    val hasUnreadPrivateMessages = unreadPrivateMessages

    // P15: connected peerID -> Noise key hex, to unify with offline favorites.
    val noiseHexByPeerID: Map<String, String> = connectedPeers.associateWith { pid ->
        try {
            lookups.peerInfo(pid)?.noisePublicKey?.hexEncodedString()
                ?: lookups.cachedNoiseKey(pid)
        } catch (_: Exception) { null }
    }.filterValues { it != null }.mapValues { it.value!! }

    // P16
    val nostrHexByPeerID: Map<String, String> = connectedPeers.associateWith { pid ->
        try {
            lookups
                .nostrPubkeyForPeerID(pid)
                ?.let { ContactIdentityResolver.nostrPubkeyHex(it) }
        } catch (_: Exception) { null }
    }.filterValues { it != null }.mapValues { it.value!! }

    // P17
    val connectedNoiseHexes = noiseHexByPeerID.values.map { it.lowercase() }.toSet()
    val connectedNostrHexes = nostrHexByPeerID.values.map { it.lowercase() }.toSet()

    fun isFavoriteMappedToConnected(favorite: FavoriteRelationship): Boolean {
        val noiseHex = ContactIdentityResolver.noiseKeyHex(favorite.peerNoisePublicKey).lowercase()
        if (connectedNoiseHexes.contains(noiseHex)) return true

        val nostrHex = favorite.peerNostrPublicKey
            ?.let { ContactIdentityResolver.nostrPubkeyHex(it) }
            ?.lowercase()
        return nostrHex != null && connectedNostrHexes.contains(nostrHex)
    }

    // P18: unread DMs first, then by most recent DM, then favorites, then alphabetical.
    // Compares the peer ID with the nickname for "You" (Q-P3).
    val sortedPeers = connectedPeers.sortedWith(
        compareBy<String> { !hasUnreadPrivateMessages.contains(it) } // Unread DM senders first
        .thenByDescending { privateChats[it]?.maxByOrNull { msg -> msg.timestamp }?.timestamp?.time ?: 0L } // Most recent DM (convert Date to Long)
        .thenBy { !(peerFavoriteStates[it] ?: false) } // Favorites first
        .thenBy { (if (it == nickname) "You" else (peerNicknames[it] ?: it)).lowercase() } // Alphabetical
    )

    // P19: base name counts across all people shown in the list (connected + offline).
    fun computeDisplayNameForPeerId(key: String): String {
        return if (key == nickname) "You" else (peerNicknames[key] ?: (privateChats[key]?.lastOrNull()?.sender ?: key.take(12)))
    }

    val baseNameCounts = mutableMapOf<String, Int>()

    // Connected peers
    sortedPeers.forEach { pid ->
        val dn = computeDisplayNameForPeerId(pid)
        val (b, _) = splitSuffix(dn)
        if (b != "You") baseNameCounts[b] = (baseNameCounts[b] ?: 0) + 1
    }

    // Offline favorites (exclude ones mapped to connected)
    val offlineFavorites = lookups.ourFavorites()
    offlineFavorites.forEach { fav ->
        val favPeerID = ContactIdentityResolver.noiseKeyHex(fav.peerNoisePublicKey)
        if (
            favPeerID.lowercase() !in excludedIdentityAliases &&
            !isFavoriteMappedToConnected(fav)
        ) {
            val dn = peerNicknames[favPeerID] ?: fav.peerNickname
            val (b, _) = splitSuffix(dn)
            if (b != "You") baseNameCounts[b] = (baseNameCounts[b] ?: 0) + 1
        }
    }

    // P20: offline favourites are appended after the connected peers.
    val offlineFavoriteRows = offlineFavorites.filterNot { favorite ->
        val favoriteNoiseKey = ContactIdentityResolver.noiseKeyHex(
            favorite.peerNoisePublicKey
        )
        favoriteNoiseKey.lowercase() in excludedIdentityAliases ||
            isFavoriteMappedToConnected(favorite)
    }
    val rowKeys: List<String> = sortedPeers +
        offlineFavoriteRows.map { ContactIdentityResolver.noiseKeyHex(it.peerNoisePublicKey) }
    // Looked up per row; the first favourite wins for a repeated key, as a scan would find it.
    val connectedPeerSet = sortedPeers.toHashSet()
    val offlineFavoriteByKey = HashMap<String, FavoriteRelationship>()
    offlineFavoriteRows.forEach { favorite ->
        offlineFavoriteByKey.putIfAbsent(
            ContactIdentityResolver.noiseKeyHex(favorite.peerNoisePublicKey),
            favorite
        )
    }

    return rowKeys.map { rowKey ->
        val connectedPeerForRow = rowKey.takeIf { it in connectedPeerSet }
        if (connectedPeerForRow != null) {
            // P21: connected peer.
            val peerID = connectedPeerForRow
            val conversationID = lookups.canonicalConversationId(peerID)
            val isFavorite = peerFavoriteStates[peerID] ?: false
            val theyFavoritedUs = peerTheyFavoritedUsStates[peerID] ?: false
            val isVerified = peerVerifiedStates[peerID] ?: false

            val noiseHex = noiseHexByPeerID[peerID]
            val meshUnread = hasUnreadPrivateMessages.contains(conversationID) || hasUnreadPrivateMessages.contains(peerID)
            val nostrUnread = if (noiseHex != null) hasUnreadPrivateMessages.contains(noiseHex) else false
            val combinedHasUnread = meshUnread || nostrUnread
            // Counts every message not sent under our nickname while the flag is set (Q-P6).
            val combinedUnreadCount = (
                privateChats[conversationID]?.count { msg -> msg.sender != nickname && meshUnread } ?: 0
            ) + (
                if (noiseHex != null) privateChats[noiseHex]?.count { msg -> msg.sender != nickname && nostrUnread } ?: 0 else 0
            )

            val displayName = if (peerID == nickname) "You" else (peerNicknames[peerID] ?: (privateChats[peerID]?.lastOrNull()?.sender ?: peerID.take(12)))
            val (bName, _) = splitSuffix(displayName)
            val showHash = (baseNameCounts[bName] ?: 0) > 1

            val isDirectLive = peerDirect[peerID] ?: try { lookups.peerInfo(peerID)?.isDirectConnection == true } catch (_: Exception) { false }
            meshPersonRow(
                peerID = peerID,
                displayName = displayName,
                isDirect = isDirectLive,
                isWifiAware = peerID in wifiAwarePeerIDs,
                isConnected = true,
                isSelected = conversationID == selectedPrivatePeer || peerID == selectedPrivatePeer,
                isFavorite = isFavorite,
                theyFavoritedUs = theyFavoritedUs,
                isVerified = isVerified,
                unreadCount = if (combinedUnreadCount > 0) combinedUnreadCount else if (combinedHasUnread) 1 else 0,
                showNostrGlobe = false,
                showHashSuffix = showHash,
                openPeerID = peerID,
                nickname = nickname,
            )
        } else {
            // P22: offline favourite, still worth showing, reachable over Nostr.
            val fav = offlineFavoriteByKey.getValue(rowKey)
            val favPeerID = rowKey

            val nostrConvKey: String? = try {
                lookups.nostrPubkeyForNoiseKey(fav.peerNoisePublicKey)
                    ?.let { ContactIdentityResolver.nostrAliasForPubkey(it) }
            } catch (_: Exception) { null }

            val conversationID = lookups.canonicalConversationId(favPeerID)
            val hasUnread = hasUnreadPrivateMessages.contains(conversationID) ||
                hasUnreadPrivateMessages.contains(favPeerID) ||
                (nostrConvKey != null && hasUnreadPrivateMessages.contains(nostrConvKey))

            // Always null in practice: P20 already dropped favourites mapped to a connected peer (Q-P8).
            val mappedConnectedPeerID = noiseHexByPeerID.entries.firstOrNull { it.value.equals(favPeerID, ignoreCase = true) }?.key
            val dn = peerNicknames[favPeerID] ?: fav.peerNickname
            val (bName, _) = splitSuffix(dn)
            val showHash = (baseNameCounts[bName] ?: 0) > 1

            val isVerified = verifiedFingerprints.contains(
                lookups.fingerprintFromNoiseBytes(fav.peerNoisePublicKey)
            )

            val unreadCount = (
                privateChats[conversationID]?.count { msg -> msg.sender != nickname && hasUnreadPrivateMessages.contains(conversationID) } ?: 0
            ) + (
                if (nostrConvKey != null) privateChats[nostrConvKey]?.count { msg -> msg.sender != nickname && hasUnreadPrivateMessages.contains(nostrConvKey) } ?: 0 else 0
            )

            meshPersonRow(
                peerID = favPeerID,
                displayName = dn,
                isDirect = false,
                isWifiAware = false,
                isConnected = false,
                isSelected = conversationID == selectedPrivatePeer || (mappedConnectedPeerID ?: favPeerID) == selectedPrivatePeer,
                isFavorite = true,
                theyFavoritedUs = fav.theyFavoritedUs,
                isVerified = isVerified,
                unreadCount = if (unreadCount > 0) unreadCount else if (hasUnread) 1 else 0,
                showNostrGlobe = (fav.isMutual && fav.peerNostrPublicKey != null),
                showHashSuffix = showHash,
                openPeerID = mappedConnectedPeerID ?: favPeerID,
                nickname = nickname,
            )
        }
    }
}

/** P25: the row's name split, "me" check (Q-P3) and colour key, as `PeerItem` derived them. */
private fun meshPersonRow(
    peerID: String,
    displayName: String,
    isDirect: Boolean,
    isWifiAware: Boolean,
    isConnected: Boolean,
    isSelected: Boolean,
    isFavorite: Boolean,
    theyFavoritedUs: Boolean,
    isVerified: Boolean,
    unreadCount: Int,
    showNostrGlobe: Boolean,
    showHashSuffix: Boolean,
    openPeerID: String,
    nickname: String,
): MeshPersonRowUi {
    val (baseNameRaw, suffixRaw) = splitSuffix(displayName)
    return MeshPersonRowUi(
        key = peerID,
        peerID = peerID,
        displayName = displayName,
        baseName = baseNameRaw,
        suffix = if (showHashSuffix) suffixRaw else "",
        showHashSuffix = showHashSuffix,
        isMe = displayName == "You" || peerID == nickname,
        colorKey = PeerIdentity.mesh(peerID).stableKey,
        isDirect = isDirect,
        isWifiAware = isWifiAware,
        isConnected = isConnected,
        isSelected = isSelected,
        isFavorite = isFavorite,
        theyFavoritedUs = theyFavoritedUs,
        isVerified = isVerified,
        unreadCount = unreadCount,
        showNostrGlobe = showNostrGlobe,
        openPeerID = openPeerID,
    )
}
