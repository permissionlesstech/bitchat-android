package com.bitchat.android.favorites

import android.content.Context
import android.util.Log
import com.bitchat.android.identity.SecureIdentityStateManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.*

/**
 * Bridging Noise and Nostr favorites
 * Direct port from iOS FavoritesPersistenceService.swift
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
 * Singleton pattern matching iOS implementation
 */
class FavoritesPersistenceService private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "FavoritesPersistenceService"
        private const val FAVORITES_KEY = "favorite_relationships"
        
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
    private val favorites = mutableMapOf<String, FavoriteRelationship>() // noiseKeyHex -> relationship
    private val listeners = mutableListOf<FavoritesChangeListener>()
    
    init {
        loadFavorites()
    }
    
    /**
     * Get favorite status for Noise public key
     */
    fun getFavoriteStatus(noisePublicKey: ByteArray): FavoriteRelationship? {
        val keyHex = noisePublicKey.joinToString("") { "%02x".format(it) }
        return favorites[keyHex]
    }
    
    /**
     * Get favorite status for 16-hex peerID
     */
    fun getFavoriteStatus(peerID: String): FavoriteRelationship? {
        // For 16-hex peerIDs, we need to find the corresponding full Noise key
        // This is a simplified lookup - in practice you'd use fingerprint matching
        for ((_, relationship) in favorites) {
            val noiseKeyHex = relationship.peerNoisePublicKey.joinToString("") { "%02x".format(it) }
            if (noiseKeyHex.startsWith(peerID)) {
                return relationship
            }
        }
        return null
    }
    
    /**
     * Update Nostr public key for a peer
     */
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
            // Create new relationship
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
    
    /**
     * Update favorite status
     */
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
    
    /**
     * Update peer favorited us status
     */
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
    
    /**
     * Get all mutual favorites
     */
    fun getMutualFavorites(): List<FavoriteRelationship> {
        return favorites.values.filter { it.isMutual }
    }
    
    /**
     * Get all favorites we have
     */
    fun getOurFavorites(): List<FavoriteRelationship> {
        return favorites.values.filter { it.isFavorite }
    }
    
    /**
     * Clear all favorites
     */
    fun clearAllFavorites() {
        favorites.clear()
        saveFavorites()
        Log.i(TAG, "Cleared all favorites")
        notifyAllCleared()
    }
    
    /**
     * Find Noise key by Nostr pubkey
     */
    fun findNoiseKey(forNostrPubkey: String): ByteArray? {
        val targetHex = normalizeNostrKeyToHex(forNostrPubkey) ?: return null
        return favorites.values.firstOrNull { rel ->
            rel.peerNostrPublicKey?.let { stored ->
                normalizeNostrKeyToHex(stored)
            } == targetHex
        }?.peerNoisePublicKey
    }
    
    /**
     * Find Nostr pubkey by Noise key
     */
    fun findNostrPubkey(forNoiseKey: ByteArray): String? {
        val keyHex = forNoiseKey.joinToString("") { "%02x".format(it) }
        return favorites[keyHex]?.peerNostrPublicKey
    }
    
    // MARK: - Private Methods
    
    private fun loadFavorites() {
        try {
            // Use public methods instead of reflection to access encrypted preferences
            val favoritesJson = stateManager.getSecureValue(FAVORITES_KEY)
            if (favoritesJson != null) {
                val type = object : TypeToken<Map<String, FavoriteRelationshipData>>() {}.type
                val data: Map<String, FavoriteRelationshipData> = gson.fromJson(favoritesJson, type)
                
                favorites.clear()
                data.forEach { (key, relationshipData) ->
                    val relationship = relationshipData.toFavoriteRelationship()
                    favorites[key] = relationship
                }
                
                Log.d(TAG, "Loaded ${favorites.size} favorite relationships")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load favorites: ${e.message}")
        }
    }
    
    private fun saveFavorites() {
        try {
            // Convert to serializable format
            val data = favorites.mapValues { (_, relationship) ->
                FavoriteRelationshipData.fromFavoriteRelationship(relationship)
            }
            
            val favoritesJson = gson.toJson(data)
            
            // Use public methods instead of reflection to access encrypted preferences
            stateManager.storeSecureValue(FAVORITES_KEY, favoritesJson)
                
            Log.d(TAG, "Saved ${favorites.size} favorite relationships")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save favorites: ${e.message}")
        }
    }

    // MARK: - Listeners
    fun addListener(listener: FavoritesChangeListener) {
        synchronized(listeners) {
            if (!listeners.contains(listener)) listeners.add(listener)
        }
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

    /**
     * Normalize a Nostr public key string (npub bech32 or hex) to lowercase hex for comparison
     */
    private fun normalizeNostrKeyToHex(value: String): String? {
        return try {
            if (value.startsWith("npub1")) {
                val (hrp, data) = com.bitchat.android.nostr.Bech32.decode(value)
                if (hrp != "npub") return null
                data.joinToString("") { "%02x".format(it) }
            } else {
                // Assume hex
                value.lowercase()
            }
        } catch (_: Exception) { null }
    }
}

/**
 * Serializable data class for JSON storage
 */
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
