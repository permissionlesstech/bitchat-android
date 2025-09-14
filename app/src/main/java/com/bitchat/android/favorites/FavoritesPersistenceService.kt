package com.bitchat.android.favorites

import android.content.Context
import android.util.Log
import com.bitchat.android.identity.SecureIdentityStateManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.*

/**
 * Bridging Noise and Nostr favorites
 * Direct port from iOS FavoritesPersistenceService.swift, with Android-specific
 * peerID (16-hex) -> npub indexing for Nostr DM routing.
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

interface FavoritesChangeListener {
    fun onFavoriteChanged(noiseKeyHex: String)
    fun onAllCleared()
}

/**
 * Manages favorites with Noise↔Nostr mapping
 * Singleton pattern matching iOS implementation.
 */
class FavoritesPersistenceService private constructor(private val context: Context) {

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

    private val stateManager = SecureIdentityStateManager(context)
    private val gson = Gson()
    private val favorites = mutableMapOf<String, FavoriteRelationship>() // noiseHex -> relationship
    // NEW: Index by current mesh peerID (16-hex) for direct lookup when sending Nostr DMs from mesh context
    private val peerIdIndex = mutableMapOf<String, String>() // peerID (lowercase 16-hex) -> npub
    private val listeners = mutableListOf<FavoritesChangeListener>()

    init {
        loadFavorites()
        loadPeerIdIndex()
    }

    /** Get favorite status for Noise public key */
    fun getFavoriteStatus(noisePublicKey: ByteArray): FavoriteRelationship? {
        val keyHex = noisePublicKey.joinToString("") { "%02x".format(it) }
        return favorites[keyHex]
    }

    /** Get favorite status for 16-hex peerID (by noiseHex prefix match) */
    fun getFavoriteStatus(peerID: String): FavoriteRelationship? {
        val pid = peerID.lowercase()
        for ((_, relationship) in favorites) {
            val noiseKeyHex = relationship.peerNoisePublicKey.joinToString("") { "%02x".format(it) }
            if (noiseKeyHex.startsWith(pid)) return relationship
        }
        return null
    }

    /** Update Nostr public key for a peer (indexed by Noise key) */
    fun updateNostrPublicKey(noisePublicKey: ByteArray, nostrPubkey: String) {
        val keyHex = noisePublicKey.joinToString("") { "%02x".format(it) }
        val existing = favorites[keyHex]

        if (existing != null) {
            val updated = existing.copy(
                peerNostrPublicKey = nostrPubkey,
                lastUpdated = Date()
            )
            favorites[keyHex] = updated
        } else {
            val relationship = FavoriteRelationship(
                peerNoisePublicKey = noisePublicKey,
                peerNostrPublicKey = nostrPubkey,
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


    /** NEW: Update Nostr pubkey for specific mesh peerID (16-hex). */
    fun updateNostrPublicKeyForPeerID(peerID: String, nostrPubkey: String) {
        val pid = peerID.lowercase()
        if (pid.length == 16 && pid.matches(Regex("^[0-9a-f]+$"))) {
            peerIdIndex[pid] = nostrPubkey
            savePeerIdIndex()
            Log.d(TAG, "Indexed npub for peerID ${pid.take(8)}…")
        } else {
            Log.w(TAG, "updateNostrPublicKeyForPeerID called with non-16hex peerID: $peerID")
        }
    }


    /** NEW: Resolve Nostr pubkey via current peerID mapping (fast path). */
    fun findNostrPubkeyForPeerID(peerID: String): String? {
        return peerIdIndex[peerID.lowercase()]
    }

    /** NEW: Resolve peerID (16-hex) for a given Nostr pubkey (npub or hex). */
    fun findPeerIDForNostrPubkey(nostrPubkey: String): String? {
        // First, try direct match in peerIdIndex (values are stored as npub strings)
        peerIdIndex.entries.firstOrNull { it.value.equals(nostrPubkey, ignoreCase = true) }?.let { return it.key }
        
        // Attempt legacy mapping via favorites Noise key association
        val targetHex = normalizeNostrKeyToHex(nostrPubkey)
        if (targetHex != null) {
            // Find relationship with matching nostr pubkey (normalized to hex) and then try to map to current peerID via noise key prefix
            val rel = favorites.values.firstOrNull { it.peerNostrPublicKey?.let { stored -> normalizeNostrKeyToHex(stored) } == targetHex }
            if (rel != null) {
                val noiseHex = rel.peerNoisePublicKey.joinToString("") { "%02x".format(it) }
                // Return 16-hex prefix as best-effort if no explicit mapping exists
                return noiseHex.take(16)
            }
        }
        return null
    }

    /** Update favorite status */
    fun updateFavoriteStatus(noisePublicKey: ByteArray, nickname: String, isFavorite: Boolean) {
        val keyHex = noisePublicKey.joinToString("") { "%02x".format(it) }

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
        val keyHex = noisePublicKey.joinToString("") { "%02x".format(it) }
        val existing = favorites[keyHex]

        if (existing != null) {
            val updated = existing.copy(
                theyFavoritedUs = theyFavoritedUs,
                lastUpdated = Date()
            )
            favorites[keyHex] = updated
            saveFavorites()
            notifyChanged(keyHex)

            Log.d(TAG, "Updated peer favorited us for ${keyHex.take(16)}...: $theyFavoritedUs")
        }
    }

    fun getMutualFavorites(): List<FavoriteRelationship> = favorites.values.filter { it.isMutual }
    fun getOurFavorites(): List<FavoriteRelationship> = favorites.values.filter { it.isFavorite }

    fun clearAllFavorites() {
        favorites.clear()
        saveFavorites()
        peerIdIndex.clear()
        savePeerIdIndex()
        Log.i(TAG, "Cleared all favorites")
        notifyAllCleared()
    }

    /** Find Noise key by Nostr pubkey */
    fun findNoiseKey(forNostrPubkey: String): ByteArray? {
        val targetHex = normalizeNostrKeyToHex(forNostrPubkey) ?: return null
        return favorites.values.firstOrNull { rel ->
            rel.peerNostrPublicKey?.let { stored -> normalizeNostrKeyToHex(stored) } == targetHex
        }?.peerNoisePublicKey
    }

    /** Find Nostr pubkey by Noise key */
    fun findNostrPubkey(forNoiseKey: ByteArray): String? {
        val keyHex = forNoiseKey.joinToString("") { "%02x".format(it) }
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
                peerIdIndex.putAll(data)
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
        synchronized(listeners) { if (!listeners.contains(listener)) listeners.add(listener) }
    }
    fun removeListener(listener: FavoritesChangeListener) {
        synchronized(listeners) { listeners.remove(listener) }
    }
    private fun notifyChanged(noiseKeyHex: String) {
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { runCatching { it.onFavoriteChanged(noiseKeyHex) } }
    }
    private fun notifyAllCleared() {
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { runCatching { it.onAllCleared() } }
    }

    /** Normalize a Nostr public key string (npub bech32 or hex) to lowercase hex */
    private fun normalizeNostrKeyToHex(value: String): String? = try {
        if (value.startsWith("npub1")) {
            val (hrp, data) = com.bitchat.android.nostr.Bech32.decode(value)
            if (hrp != "npub") null else data.joinToString("") { "%02x".format(it) }
        } else value.lowercase()
    } catch (_: Exception) { null }
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
                peerNoisePublicKeyHex = relationship.peerNoisePublicKey.joinToString("") { "%02x".format(it) },
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
        val noiseKeyBytes = peerNoisePublicKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
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
