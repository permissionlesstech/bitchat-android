package com.bitchat.android.favorites

import android.content.Context
import android.util.Log
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.services.ContactIdentityResolver
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.*

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
    val lastUpdated: Date,
    val peerUpdatedAt: Long = 0,
    val peerUpdateID: String = "",
    val pendingControlID: String? = null,
    val pendingControlTimestamp: Long = 0
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
 * Singleton pattern matching iOS implementation.
 */
class FavoritesPersistenceService internal constructor(
    private val context: Context,
    private val stateManager: SecureIdentityStateManager = SecureIdentityStateManager(context)
) {

    companion object {
        private const val TAG = "FavoritesPersistenceService"
        private const val FAVORITES_KEY = "favorite_relationships"            // noiseHex -> relationship
        private const val PEERID_INDEX_KEY = "favorite_peerid_index"         // peerID(16-hex) -> npub

        @Volatile
        private var INSTANCE: FavoritesPersistenceService? = null

        val shared: FavoritesPersistenceService
            get() = INSTANCE ?: throw IllegalStateException("FavoritesPersistenceService not initialized")

        fun initialize(context: Context) {
            if (INSTANCE == null) {
                synchronized(this) {
                    if (INSTANCE == null) {
                        INSTANCE = FavoritesPersistenceService(context.applicationContext)
                    }
                }
            }
        }
    }

    private val gson = Gson()
    private val favorites = mutableMapOf<String, FavoriteRelationship>() // noiseHex -> relationship
    private var persistedFavorites: Map<String, FavoriteRelationship> = emptyMap()
    private val peerIdIndex = mutableMapOf<String, String>() // peerID (lowercase 16-hex) -> npub
    private val listeners = mutableListOf<FavoritesChangeListener>()

    init {
        loadFavorites()
        persistedFavorites = favorites.toMap()
        loadPeerIdIndex()
    }

    /** Get favorite status for Noise public key */
    @Synchronized
    fun getFavoriteStatus(noisePublicKey: ByteArray): FavoriteRelationship? {
        val keyHex = ContactIdentityResolver.noiseKeyHex(noisePublicKey)
        return favorites[keyHex]
    }

    /** Get favorite status for a mesh peer ID or full Noise public key hex. */
    @Synchronized
    fun getFavoriteStatus(peerID: String): FavoriteRelationship? {
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
    @Synchronized
    fun updateNostrPublicKey(noisePublicKey: ByteArray, nostrPubkey: String) {
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
    }


    /** Update Nostr pubkey for a specific mesh peerID. */
    @Synchronized
    fun updateNostrPublicKeyForPeerID(peerID: String, nostrPubkey: String) {
        val pid = peerID.trim().lowercase()
        val normalizedNpub = ContactIdentityResolver.nostrPubkeyHex(nostrPubkey)
            ?.let { ContactIdentityResolver.npubFromHex(it) }
            ?: nostrPubkey
        if (ContactIdentityResolver.isMeshPeerId(pid)) {
            peerIdIndex[pid] = normalizedNpub
            savePeerIdIndex()
            notifyChanged(pid)
        } else {
        }
    }


    /** Resolve Nostr pubkey via current peerID mapping or stored Noise identity. */
    @Synchronized
    fun findNostrPubkeyForPeerID(peerID: String): String? {
        val pid = peerID.trim().lowercase()
        return peerIdIndex[pid] ?: getFavoriteStatus(pid)?.peerNostrPublicKey
    }

    /** Resolve mesh peerID for a given Nostr pubkey (npub or hex). */
    @Synchronized
    fun findPeerIDForNostrPubkey(nostrPubkey: String): String? {
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
    @Synchronized
    fun updateFavoriteStatus(noisePublicKey: ByteArray, nickname: String, isFavorite: Boolean) {
        val keyHex = ContactIdentityResolver.noiseKeyHex(noisePublicKey)

        val existing = favorites[keyHex]

        val updated = if (existing != null) {
            existing.copy(
                peerNickname = nickname,
                isFavorite = isFavorite,
                lastUpdated = Date(),
                favoritedAt = if (isFavorite && !existing.isFavorite) Date() else existing.favoritedAt,
                pendingControlID = if (existing.isFavorite != isFavorite) UUID.randomUUID().toString() else existing.pendingControlID,
                pendingControlTimestamp = if (existing.isFavorite != isFavorite) maxOf(System.currentTimeMillis(), existing.pendingControlTimestamp + 1) else existing.pendingControlTimestamp
            )
        } else {
            FavoriteRelationship(
                peerNoisePublicKey = noisePublicKey,
                peerNostrPublicKey = null,
                peerNickname = nickname,
                isFavorite = isFavorite,
                theyFavoritedUs = false,
                favoritedAt = Date(),
                lastUpdated = Date(),
                pendingControlID = UUID.randomUUID().toString(),
                pendingControlTimestamp = System.currentTimeMillis()
            )
        }

        favorites[keyHex] = updated
        saveFavorites()
        notifyChanged(keyHex)

    }

    @Synchronized
    fun acknowledgeLocalControl(conversationID: String, messageID: String) {
        val entry = favorites.entries.firstOrNull {
            ContactIdentityResolver.contactConversationIdForNoiseKey(it.value.peerNoisePublicKey) == conversationID &&
                it.value.pendingControlID == messageID
        } ?: return
        favorites[entry.key] = entry.value.copy(pendingControlID = null)
        saveFavorites()
    }

    /** Authenticated remote state is ordered by its original packet time, never relay order. */
    @Synchronized
    fun applyRemoteFavorite(noisePublicKey: ByteArray, value: Boolean, timestamp: Long, messageID: String, nostrPubkey: String? = null): Boolean {
        if (timestamp <= 0 || timestamp > System.currentTimeMillis() + 900_000) return false
        val normalizedNostrKey = nostrPubkey?.let { ContactIdentityResolver.nostrPubkeyHex(it) ?: return false }
        val key = ContactIdentityResolver.noiseKeyHex(noisePublicKey)
        val current = favorites[key]
        if (current != null && (timestamp < current.peerUpdatedAt ||
                (timestamp == current.peerUpdatedAt && messageID <= current.peerUpdateID))) return false
        favorites[key] = current.withPeerFavoritedUs(noisePublicKey, value)
            .copy(peerUpdatedAt = timestamp, peerUpdateID = messageID,
                peerNostrPublicKey = normalizedNostrKey ?: current?.peerNostrPublicKey)
        saveFavorites()
        notifyChanged(key)
        return true
    }

    /** Update peer favorited-us flag */
    @Synchronized
    fun updatePeerFavoritedUs(noisePublicKey: ByteArray, theyFavoritedUs: Boolean) {
        val keyHex = ContactIdentityResolver.noiseKeyHex(noisePublicKey)
        val existing = favorites[keyHex]
        val updated = existing.withPeerFavoritedUs(noisePublicKey, theyFavoritedUs)

        favorites[keyHex] = updated
        saveFavorites()
        notifyChanged(keyHex)

    }

    @Synchronized
    fun getMutualFavorites(): List<FavoriteRelationship> = favorites.values.filter { it.isMutual }
    @Synchronized
    fun getOurFavorites(): List<FavoriteRelationship> = favorites.values.filter { it.isFavorite }
    @Synchronized
    fun getAllRelationships(): List<FavoriteRelationship> = favorites.values.toList()

    @Synchronized
    fun clearAllFavorites() {
        favorites.clear()
        saveFavorites()
        peerIdIndex.clear()
        savePeerIdIndex()
        Log.i(TAG, "Cleared all favorites")
        notifyAllCleared()
    }

    /** Find Noise key by Nostr pubkey */
    @Synchronized
    fun findNoiseKey(forNostrPubkey: String): ByteArray? {
        val targetHex = ContactIdentityResolver.nostrPubkeyHex(forNostrPubkey) ?: return null
        return favorites.values.firstOrNull { rel ->
            rel.peerNostrPublicKey?.let { stored -> ContactIdentityResolver.nostrPubkeyHex(stored) } == targetHex
        }?.peerNoisePublicKey
    }

    /** Find Nostr pubkey by Noise key */
    @Synchronized
    fun findNostrPubkey(forNoiseKey: ByteArray): String? {
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
            Log.e(TAG, "Failed to load favorites")
        }
    }

    private fun saveFavorites() {
        try {
            val data = favorites.mapValues { (_, relationship) ->
                FavoriteRelationshipData.fromFavoriteRelationship(relationship)
            }
            val favoritesJson = gson.toJson(data)
            check(stateManager.storeSecureValueAndWait(FAVORITES_KEY, favoritesJson)) { "Unable to persist relationship" }
            persistedFavorites = favorites.toMap()
            Log.d(TAG, "Saved ${favorites.size} favorite relationships")
        } catch (e: Exception) {
            favorites.clear()
            favorites.putAll(persistedFavorites)
            throw IllegalStateException("Unable to persist relationship", e)
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
            Log.e(TAG, "Failed to load peerID index")
        }
    }

    private fun savePeerIdIndex() {
        try {
            val json = gson.toJson(peerIdIndex)
            stateManager.storeSecureValue(PEERID_INDEX_KEY, json)
            Log.d(TAG, "Saved ${peerIdIndex.size} peerID→npub mappings")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save peerID index")
        }
    }

    // MARK: - Listeners
    @Synchronized
    fun addListener(listener: FavoritesChangeListener) {
        synchronized(listeners) { if (!listeners.contains(listener)) listeners.add(listener) }
    }
    @Synchronized
    fun removeListener(listener: FavoritesChangeListener) {
        synchronized(listeners) { listeners.remove(listener) }
    }
    private fun notifyChanged(noiseKeyHex: String) {
        val snapshot = synchronized(listeners) { listeners.toList() }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching { AppStateStore.canonicalizePrivateChats() }
            snapshot.forEach { runCatching { it.onFavoriteChanged(noiseKeyHex) } }
        }
    }
    private fun notifyAllCleared() {
        val snapshot = synchronized(listeners) { listeners.toList() }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            snapshot.forEach { runCatching { it.onAllCleared() } }
        }
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
    val lastUpdated: Long,
    val peerUpdatedAt: Long = 0,
    val peerUpdateID: String? = null,
    val pendingControlID: String? = null,
    val pendingControlTimestamp: Long = 0
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
                lastUpdated = relationship.lastUpdated.time,
                peerUpdatedAt = relationship.peerUpdatedAt,
                peerUpdateID = relationship.peerUpdateID,
                pendingControlID = relationship.pendingControlID,
                pendingControlTimestamp = relationship.pendingControlTimestamp
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
            lastUpdated = Date(lastUpdated),
            peerUpdatedAt = peerUpdatedAt,
            peerUpdateID = peerUpdateID.orEmpty(),
            pendingControlID = pendingControlID,
            pendingControlTimestamp = pendingControlTimestamp
        )
    }
}
