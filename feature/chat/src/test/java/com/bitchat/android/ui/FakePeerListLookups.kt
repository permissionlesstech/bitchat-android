package com.bitchat.android.ui

import com.bitchat.android.favorites.FavoriteRelationship
import com.bitchat.android.mesh.PeerInfo
import com.bitchat.android.services.ContactIdentityResolver
import java.util.Date

/** In-memory [PeerListLookups] with synthetic data; unset lookups answer "nothing known". */
internal class FakePeerListLookups(
    var aliases: Map<String, Set<String>> = emptyMap(),
    var aliasFailure: Boolean = false,
    var canonical: Map<String, String> = emptyMap(),
    var favorites: Set<String> = emptySet(),
    var favoriteStatuses: Map<String, FavoriteRelationship> = emptyMap(),
    var favoriteStatusFailure: Boolean = false,
    var ourFavoritesList: List<FavoriteRelationship> = emptyList(),
    var nostrByPeerID: Map<String, String> = emptyMap(),
    var nostrByNoiseHex: Map<String, String> = emptyMap(),
    var peerInfos: Map<String, PeerInfo> = emptyMap(),
    var cachedNoiseKeys: Map<String, String> = emptyMap(),
    var displayFingerprints: Map<String, String> = emptyMap(),
    var geohashIdentityHex: String? = null,
) : PeerListLookups {
    val isFavoriteCalls = mutableListOf<String>()

    override fun aliasesForConversation(peerOrConversationID: String): Set<String> {
        if (aliasFailure) throw IllegalStateException("not initialised")
        return aliases[peerOrConversationID] ?: setOf(peerOrConversationID)
    }

    override fun canonicalConversationId(peerOrConversationID: String): String =
        canonical[peerOrConversationID] ?: peerOrConversationID

    override fun isFavorite(peerID: String): Boolean {
        isFavoriteCalls += peerID
        return peerID in favorites
    }

    override fun favoriteStatus(peerID: String): FavoriteRelationship? {
        if (favoriteStatusFailure) throw IllegalStateException("not initialised")
        return favoriteStatuses[peerID]
    }

    override fun ourFavorites(): List<FavoriteRelationship> = ourFavoritesList

    override fun nostrPubkeyForPeerID(peerID: String): String? = nostrByPeerID[peerID]

    override fun nostrPubkeyForNoiseKey(noisePublicKey: ByteArray): String? =
        nostrByNoiseHex[ContactIdentityResolver.noiseKeyHex(noisePublicKey)]

    override fun peerInfo(peerID: String): PeerInfo? = peerInfos[peerID]

    override fun cachedNoiseKey(peerID: String): String? = cachedNoiseKeys[peerID]

    override fun peerFingerprintForDisplay(peerID: String): String? = displayFingerprints[peerID]

    override fun fingerprintFromNoiseBytes(noisePublicKey: ByteArray): String =
        ContactIdentityResolver.fingerprintHex(noisePublicKey)

    override fun geohashIdentityPublicKeyHex(geohash: String): String? = geohashIdentityHex
}

/** A synthetic 32-byte Noise key filled with [fill]. */
internal fun syntheticNoiseKey(fill: Int): ByteArray = ByteArray(32) { fill.toByte() }

internal fun favoriteRelationship(
    noiseKey: ByteArray,
    nickname: String,
    nostrPublicKey: String? = null,
    isFavorite: Boolean = true,
    theyFavoritedUs: Boolean = false,
) = FavoriteRelationship(
    peerNoisePublicKey = noiseKey,
    peerNostrPublicKey = nostrPublicKey,
    peerNickname = nickname,
    isFavorite = isFavorite,
    theyFavoritedUs = theyFavoritedUs,
    favoritedAt = Date(PEER_LIST_NOW_MILLIS),
    lastUpdated = Date(PEER_LIST_NOW_MILLIS),
)

internal fun meshPeerInfo(
    peerID: String,
    noiseKey: ByteArray? = null,
    isDirect: Boolean = false,
) = PeerInfo(
    id = peerID,
    nickname = "",
    isConnected = true,
    isDirectConnection = isDirect,
    noisePublicKey = noiseKey,
    signingPublicKey = null,
    isVerifiedNickname = false,
    lastSeen = PEER_LIST_NOW_MILLIS,
)

/** Fixed clock for every peer-list test. */
internal const val PEER_LIST_NOW_MILLIS = 1_700_000_000_000L
