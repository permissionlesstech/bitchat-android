package com.bitchat.android.favorites

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.services.ContactIdentityResolver
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridging Noise and Nostr favorites
 */
data class FavoriteRelationship(
    val peerNoisePublicKey: ByteArray,    // Noise static public key (32 bytes)
    val peerNostrPublicKey: String?,      // npub bech32 string
    val peerNickname: String,
    val isFavorite: Boolean,              // We favorited them
    val theyFavoritedUs: Boolean,         // They favorited us
    val favoritedAt: Date,
    val lastUpdated: Date
) {
    val isMutual: Boolean get() = isFavorite && theyFavoritedUs

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FavoriteRelationship

        if (!peerNoisePublicKey.contentEquals(other.peerNoisePublicKey)) return false
        if (peerNostrPublicKey != other.peerNostrPublicKey) return false
        if (peerNickname != other.peerNickname) return false
        if (isFavorite != other.isFavorite) return false
        if (theyFavoritedUs != other.theyFavoritedUs) return false

        return true
    }

    override fun hashCode(): Int {
        var result = peerNoisePublicKey.contentHashCode()
        result = 31 * result + (peerNostrPublicKey?.hashCode() ?: 0)
        result = 31 * result + peerNickname.hashCode()
        result = 31 * result + isFavorite.hashCode()
        result = 31 * result + theyFavoritedUs.hashCode()
        return result
    }
}

internal fun FavoriteRelationship?.withPeerFavoritedUs(
    noisePublicKey: ByteArray,
    theyFavoritedUs: Boolean,
    now: Date = Date()
): FavoriteRelationship {
    return this?.copy(
        theyFavoritedUs = theyFavoritedUs,
        lastUpdated = now
    ) ?: FavoriteRelationship(
        peerNoisePublicKey = noisePublicKey,
        peerNostrPublicKey = null,
        peerNickname = "Unknown",
        isFavorite = false,
        theyFavoritedUs = theyFavoritedUs,
        favoritedAt = now,
        lastUpdated = now
    )
}

interface FavoritesChangeListener {
    fun onFavoriteChanged(noiseKeyHex: String)
    fun onAllCleared()
}

/**
 * Manages favorites with Noise↔Nostr mapping
 * One instance per process, matching iOS implementation.
 *
 * The constructor does no I/O. [initialize] opens the secure store and loads the saved
 * relationships; until it has succeeded, every public method throws
 * `IllegalStateException("FavoritesPersistenceService not initialized")`, as the old static
 * accessor did, and a failed attempt is retried by the next call.
 */
@Singleton
class FavoritesPersistenceService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appStateStore: AppStateStore,
) {

    private companion object {
        const val TAG = "FavoritesPersistenceService"
        const val FAVORITES_KEY = "favorite_relationships"            // noiseHex -> relationship
        const val PEERID_INDEX_KEY = "favorite_peerid_index"         // peerID(16-hex) -> npub
    }

    @Volatile
    private var initialized = false

    private lateinit var stateManager: SecureIdentityStateManager
    private val gson = Gson()
    private val favorites = mutableMapOf<String, FavoriteRelationship>() // noiseHex -> relationship
    private val peerIdIndex = mutableMapOf<String, String>() // peerID (lowercase 16-hex) -> npub
    private val listeners = mutableListOf<FavoritesChangeListener>()

    /**
     * Opens the secure store and loads the saved favourites, once. If the store cannot be opened
     * the exception propagates, nothing is marked done, and a later call tries again.
     */
    fun initialize() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            stateManager = SecureIdentityStateManager(context)
            loadFavorites()
            loadPeerIdIndex()
            initialized = true
        }
    }

    private fun checkInitialized() {
        check(initialized) { "FavoritesPersistenceService not initialized" }
    }

    /** Get favorite status for Noise public key */
    fun getFavoriteStatus(noisePublicKey: ByteArray): FavoriteRelationship? {
        checkInitialized()
        val keyHex = ContactIdentityResolver.noiseKeyHex(noisePublicKey)
        return favorites[keyHex]
    }

    /** Get favorite status for a mesh peer ID or full Noise public key hex. */
    fun getFavoriteStatus(peerID: String): FavoriteRelationship? {
        checkInitialized()
        val pid = peerID.trim().lowercase()

        if (ContactIdentityResolver.isNoiseKeyHex(pid)) {
            return favorites[pid]
        }

        ContactIdentityResolver.fingerprintFromContactConversationId(pid)?.let { fingerprint ->
            return favorites.values.firstOrNull { relationship ->
                ContactIdentityResolver.fingerprintHex(relationship.peerNoisePublicKey)
                    .equals(fingerprint, ignoreCase = true)
            }
        }

        if (ContactIdentityResolver.isMeshPeerId(pid)) {
            peerIdIndex[pid]?.let { indexedNpub ->
                findNoiseKey(indexedNpub)?.let { return getFavoriteStatus(it) }
            }
            return favorites.values.firstOrNull { relationship ->
                ContactIdentityResolver.peerIdForNoiseKey(relationship.peerNoisePublicKey) == pid
            }
        }

        return null
    }

    /** Update Nostr public key for a peer (indexed by Noise key) */
    fun updateNostrPublicKey(noisePublicKey: ByteArray, nostrPubkey: String) {
        checkInitialized()
        val keyHex = ContactIdentityResolver.noiseKeyHex(noisePublicKey)
        val normalizedNpub = ContactIdentityResolver.nostrPubkeyHex(nostrPubkey)
            ?.let { ContactIdentityResolver.npubFromHex(it) }
            ?: nostrPubkey
        val existing = favorites[keyHex]

        if (existing != null) {
            val updated = existing.copy(
                peerNostrPublicKey = normalizedNpub,
                lastUpdated = Date()
            )
            favorites[keyHex] = updated
        } else {
            val relationship = FavoriteRelationship(
                peerNoisePublicKey = noisePublicKey,
                peerNostrPublicKey = normalizedNpub,
                peerNickname = "Unknown",
                isFavorite = false,
                theyFavoritedUs = false,
                favoritedAt = Date(),
                lastUpdated = Date()
            )
            favorites[keyHex] = relationship
        }

        saveFavorites()
        notifyChanged(keyHex)
        Log.d(TAG, "Updated Nostr pubkey association for ${keyHex.take(16)}...")
    }


    /** Update Nostr pubkey for a specific mesh peerID. */
    fun updateNostrPublicKeyForPeerID(peerID: String, nostrPubkey: String) {
        checkInitialized()
        val pid = peerID.trim().lowercase()
        val normalizedNpub = ContactIdentityResolver.nostrPubkeyHex(nostrPubkey)
            ?.let { ContactIdentityResolver.npubFromHex(it) }
            ?: nostrPubkey
        if (ContactIdentityResolver.isMeshPeerId(pid)) {
            peerIdIndex[pid] = normalizedNpub
            savePeerIdIndex()
            notifyChanged(pid)
            Log.d(TAG, "Indexed npub for peerID ${pid.take(8)}…")
        } else {
            Log.w(TAG, "updateNostrPublicKeyForPeerID called with non-16hex peerID: $peerID")
        }
    }


    /** Resolve Nostr pubkey via current peerID mapping or stored Noise identity. */
    fun findNostrPubkeyForPeerID(peerID: String): String? {
        checkInitialized()
        val pid = peerID.trim().lowercase()
        return peerIdIndex[pid] ?: getFavoriteStatus(pid)?.peerNostrPublicKey
    }

    /** Resolve mesh peerID for a given Nostr pubkey (npub or hex). */
    fun findPeerIDForNostrPubkey(nostrPubkey: String): String? {
        checkInitialized()
        val targetHex = ContactIdentityResolver.nostrPubkeyHex(nostrPubkey) ?: return null

        peerIdIndex.entries.firstOrNull { (_, stored) ->
            ContactIdentityResolver.nostrPubkeyHex(stored) == targetHex
        }?.let { return it.key }

        favorites.values.firstOrNull { relationship ->
            relationship.peerNostrPublicKey?.let { ContactIdentityResolver.nostrPubkeyHex(it) } == targetHex
        }?.let { relationship ->
            return ContactIdentityResolver.peerIdForNoiseKey(relationship.peerNoisePublicKey)
        }

        return null
    }

    /** Update favorite status */
    fun updateFavoriteStatus(noisePublicKey: ByteArray, nickname: String, isFavorite: Boolean) {
        checkInitialized()
        val keyHex = ContactIdentityResolver.noiseKeyHex(noisePublicKey)

        val existing = favorites[keyHex]

        val updated = if (existing != null) {
            existing.copy(
                peerNickname = nickname,
                isFavorite = isFavorite,
                lastUpdated = Date(),
                favoritedAt = if (isFavorite && !existing.isFavorite) Date() else existing.favoritedAt
            )
        } else {
            FavoriteRelationship(
                peerNoisePublicKey = noisePublicKey,
                peerNostrPublicKey = null,
                peerNickname = nickname,
                isFavorite = isFavorite,
                theyFavoritedUs = false,
                favoritedAt = Date(),
                lastUpdated = Date()
            )
        }

        favorites[keyHex] = updated
        saveFavorites()
        notifyChanged(keyHex)

        Log.d(TAG, "Updated favorite status for $nickname: $isFavorite")
    }

    /** Update peer favorited-us flag */
    fun updatePeerFavoritedUs(noisePublicKey: ByteArray, theyFavoritedUs: Boolean) {
        checkInitialized()
        val keyHex = ContactIdentityResolver.noiseKeyHex(noisePublicKey)
        val existing = favorites[keyHex]
        val updated = existing.withPeerFavoritedUs(noisePublicKey, theyFavoritedUs)

        favorites[keyHex] = updated
        saveFavorites()
        notifyChanged(keyHex)

        Log.d(TAG, "Updated peer favorited us for ${keyHex.take(16)}...: $theyFavoritedUs")
    }

    fun getMutualFavorites(): List<FavoriteRelationship> {
        checkInitialized()
        return favorites.values.filter { it.isMutual }
    }
    fun getOurFavorites(): List<FavoriteRelationship> {
        checkInitialized()
        return favorites.values.filter { it.isFavorite }
    }
    fun getAllRelationships(): List<FavoriteRelationship> {
        checkInitialized()
        return favorites.values.toList()
    }

    fun clearAllFavorites() {
        checkInitialized()
        favorites.clear()
        saveFavorites()
        peerIdIndex.clear()
        savePeerIdIndex()
        Log.i(TAG, "Cleared all favorites")
        notifyAllCleared()
    }

    /** Find Noise key by Nostr pubkey */
    fun findNoiseKey(forNostrPubkey: String): ByteArray? {
        checkInitialized()
        val targetHex = ContactIdentityResolver.nostrPubkeyHex(forNostrPubkey) ?: return null
        return favorites.values.firstOrNull { rel ->
            rel.peerNostrPublicKey?.let { stored -> ContactIdentityResolver.nostrPubkeyHex(stored) } == targetHex
        }?.peerNoisePublicKey
    }

    /** Find Nostr pubkey by Noise key */
    fun findNostrPubkey(forNoiseKey: ByteArray): String? {
        checkInitialized()
        val keyHex = ContactIdentityResolver.noiseKeyHex(forNoiseKey)
        return favorites[keyHex]?.peerNostrPublicKey
    }

    // MARK: - Persistence

    private fun loadFavorites() {
        try {
            val favoritesJson = stateManager.getSecureValue(FAVORITES_KEY)
            if (favoritesJson != null) {
                val type = object : TypeToken<Map<String, FavoriteRelationshipData>>() {}.type
                val data: Map<String, FavoriteRelationshipData> = gson.fromJson(favoritesJson, type)

                favorites.clear()
                data.forEach { (key, relationshipData) ->
                    favorites[key] = relationshipData.toFavoriteRelationship()
                }
                Log.d(TAG, "Loaded ${favorites.size} favorite relationships")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load favorites: ${e.message}")
        }
    }

    private fun saveFavorites() {
        try {
            val data = favorites.mapValues { (_, relationship) ->
                FavoriteRelationshipData.fromFavoriteRelationship(relationship)
            }
            val favoritesJson = gson.toJson(data)
            stateManager.storeSecureValue(FAVORITES_KEY, favoritesJson)
            Log.d(TAG, "Saved ${favorites.size} favorite relationships")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save favorites: ${e.message}")
        }
    }

    private fun loadPeerIdIndex() {
        try {
            val json = stateManager.getSecureValue(PEERID_INDEX_KEY)
            if (json != null) {
                val type = object : TypeToken<Map<String, String>>() {}.type
                val data: Map<String, String> = gson.fromJson(json, type)
                peerIdIndex.clear()
                data.forEach { (peerID, npub) ->
                    val normalizedPeerID = peerID.lowercase()
                    if (ContactIdentityResolver.isMeshPeerId(normalizedPeerID)) {
                        peerIdIndex[normalizedPeerID] = npub
                    }
                }
                Log.d(TAG, "Loaded ${peerIdIndex.size} peerID→npub mappings")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load peerID index: ${e.message}")
        }
    }

    private fun savePeerIdIndex() {
        try {
            val json = gson.toJson(peerIdIndex)
            stateManager.storeSecureValue(PEERID_INDEX_KEY, json)
            Log.d(TAG, "Saved ${peerIdIndex.size} peerID→npub mappings")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save peerID index: ${e.message}")
        }
    }

    // MARK: - Listeners
    fun addListener(listener: FavoritesChangeListener) {
        checkInitialized()
        synchronized(listeners) { if (!listeners.contains(listener)) listeners.add(listener) }
    }
    fun removeListener(listener: FavoritesChangeListener) {
        checkInitialized()
        synchronized(listeners) { listeners.remove(listener) }
    }
    private fun notifyChanged(noiseKeyHex: String) {
        runCatching { appStateStore.canonicalizePrivateChats() }
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { runCatching { it.onFavoriteChanged(noiseKeyHex) } }
    }
    private fun notifyAllCleared() {
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { runCatching { it.onAllCleared() } }
    }
}

/** Serializable data for JSON storage */
private data class FavoriteRelationshipData(
    val peerNoisePublicKeyHex: String,
    val peerNostrPublicKey: String?,
    val peerNickname: String,
    val isFavorite: Boolean,
    val theyFavoritedUs: Boolean,
    val favoritedAt: Long,
    val lastUpdated: Long
) {
    companion object {
        fun fromFavoriteRelationship(relationship: FavoriteRelationship): FavoriteRelationshipData {
            return FavoriteRelationshipData(
                peerNoisePublicKeyHex = ContactIdentityResolver.noiseKeyHex(relationship.peerNoisePublicKey),
                peerNostrPublicKey = relationship.peerNostrPublicKey,
                peerNickname = relationship.peerNickname,
                isFavorite = relationship.isFavorite,
                theyFavoritedUs = relationship.theyFavoritedUs,
                favoritedAt = relationship.favoritedAt.time,
                lastUpdated = relationship.lastUpdated.time
            )
        }
    }

    fun toFavoriteRelationship(): FavoriteRelationship {
        val noiseKeyBytes = ContactIdentityResolver.bytesFromHex(peerNoisePublicKeyHex) ?: ByteArray(0)
        return FavoriteRelationship(
            peerNoisePublicKey = noiseKeyBytes,
            peerNostrPublicKey = peerNostrPublicKey,
            peerNickname = peerNickname,
            isFavorite = isFavorite,
            theyFavoritedUs = theyFavoritedUs,
            favoritedAt = Date(favoritedAt),
            lastUpdated = Date(lastUpdated)
        )
    }
}
