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
    val peerNdrSessionPubkeyHex: String? = null,
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
        if (peerNdrSessionPubkeyHex != other.peerNdrSessionPubkeyHex) return false
        if (peerNickname != other.peerNickname) return false
        if (isFavorite != other.isFavorite) return false
        if (theyFavoritedUs != other.theyFavoritedUs) return false

        return true
    }

    override fun hashCode(): Int {
        var result = peerNoisePublicKey.contentHashCode()
        result = 31 * result + (peerNostrPublicKey?.hashCode() ?: 0)
        result = 31 * result + (peerNdrSessionPubkeyHex?.hashCode() ?: 0)
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
class FavoritesPersistenceService private constructor(
    private val stateManager: SecureIdentityStateManager
) {
    internal constructor(
        stateManager: SecureIdentityStateManager,
        testOnly: Boolean
    ) : this(stateManager) {
        require(testOnly) { "Injected favorites storage is test-only" }
    }

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
                        INSTANCE = FavoritesPersistenceService(
                            SecureIdentityStateManager(context.applicationContext)
                        )
                    }
                }
            }
        }
    }

    private val gson = Gson()
    private val favorites = mutableMapOf<String, FavoriteRelationship>() // noiseHex -> relationship
    private val peerIdIndex = mutableMapOf<String, String>() // peerID (lowercase 16-hex) -> npub
    private val listeners = mutableListOf<FavoritesChangeListener>()
    private var ndrPeerRetirementGuard: ((oldPeerPubkeyHex: String) -> Boolean)? = null
    private val ndrRebindsInProgress = mutableSetOf<String>()

    init {
        loadFavorites()
        loadPeerIdIndex()
    }

    /** Get favorite status for Noise public key */
    @Synchronized
    fun getFavoriteStatus(noisePublicKey: ByteArray): FavoriteRelationship? {
        val keyHex = ContactIdentityResolver.noiseKeyHex(noisePublicKey)
        return favorites[keyHex]
    }

    /** Get favorite status for a mesh peer ID or full Noise public key hex. */
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
    fun updateNostrPublicKey(noisePublicKey: ByteArray, nostrPubkey: String): Boolean {
        val keyHex = ContactIdentityResolver.noiseKeyHex(noisePublicKey)
        val normalizedHex = ContactIdentityResolver.nostrPubkeyHex(nostrPubkey) ?: return false
        val normalizedNpub = ContactIdentityResolver.npubFromHex(normalizedHex) ?: return false
        var oldPeerPubkeyHex: String? = null
        var originalNostrHex: String? = null
        var originalNdrHex: String? = null
        synchronized(this) {
            if (keyHex in ndrRebindsInProgress ||
                isIdentityBoundToAnotherFavorite(keyHex, normalizedHex)
            ) return false
            val existing = favorites[keyHex]
            val oldPeer = existing?.let(::effectiveNdrPeerPubkeyHex)
            val isRebind = oldPeer != null &&
                !oldPeer.equals(normalizedHex, ignoreCase = true)
            val mustRetire = isRebind &&
                !isIdentityReferencedByAnotherFavorite(keyHex, oldPeer)
            if (!mustRetire) {
                favorites[keyHex] = relationshipWithNostrIdentity(
                    existing = existing,
                    noisePublicKey = noisePublicKey,
                    normalizedNpub = normalizedNpub,
                    clearExplicitNdrPeer = isRebind
                )
                saveFavorites()
            } else {
                ndrRebindsInProgress.add(keyHex)
                oldPeerPubkeyHex = oldPeer
                originalNostrHex = existing.peerNostrPublicKey
                    ?.let(ContactIdentityResolver::nostrPubkeyHex)
                originalNdrHex = existing.peerNdrSessionPubkeyHex
            }
        }

        val peerToRetire = oldPeerPubkeyHex
        if (peerToRetire != null) {
            if (!retireBeforeRebind(peerToRetire)) {
                synchronized(this) { ndrRebindsInProgress.remove(keyHex) }
                Log.e(TAG, "Refusing Nostr identity rebind before old NDR peer is retired")
                return false
            }
            val committed = synchronized(this) {
                val current = favorites[keyHex]
                val bindingUnchanged =
                    current?.peerNostrPublicKey
                        ?.let(ContactIdentityResolver::nostrPubkeyHex) == originalNostrHex &&
                        current?.peerNdrSessionPubkeyHex == originalNdrHex
                val canCommit = bindingUnchanged &&
                    !isIdentityBoundToAnotherFavorite(keyHex, normalizedHex)
                if (canCommit) {
                    favorites[keyHex] = relationshipWithNostrIdentity(
                        existing = current,
                        noisePublicKey = noisePublicKey,
                        normalizedNpub = normalizedNpub,
                        clearExplicitNdrPeer = true
                    )
                    saveFavorites()
                }
                ndrRebindsInProgress.remove(keyHex)
                canCommit
            }
            if (!committed) return false
        }

        notifyChanged(keyHex)
        Log.d(TAG, "Updated Nostr pubkey association for ${keyHex.take(16)}...")
        return true
    }


    /** Update Nostr pubkey for a specific mesh peerID. */
    fun updateNostrPublicKeyForPeerID(peerID: String, nostrPubkey: String) {
        val pid = peerID.trim().lowercase()
        val normalizedNpub = ContactIdentityResolver.nostrPubkeyHex(nostrPubkey)
            ?.let { ContactIdentityResolver.npubFromHex(it) }
            ?: nostrPubkey
        if (ContactIdentityResolver.isMeshPeerId(pid)) {
            peerIdIndex[pid] = normalizedNpub
            savePeerIdIndex()
            Log.d(TAG, "Indexed npub for peerID ${pid.take(8)}…")
        } else {
            Log.w(TAG, "updateNostrPublicKeyForPeerID called with non-16hex peerID: $peerID")
        }
    }


    /** Resolve Nostr pubkey via current peerID mapping or stored Noise identity. */
    fun findNostrPubkeyForPeerID(peerID: String): String? {
        val pid = peerID.trim().lowercase()
        return peerIdIndex[pid] ?: getFavoriteStatus(pid)?.peerNostrPublicKey
    }

    /** Resolve mesh peerID for a given Nostr pubkey (npub or hex). */
    fun findPeerIDForNostrPubkey(nostrPubkey: String): String? {
        val targetHex = ContactIdentityResolver.nostrPubkeyHex(nostrPubkey) ?: return null

        peerIdIndex.entries.firstOrNull { (_, stored) ->
            ContactIdentityResolver.nostrPubkeyHex(stored) == targetHex
        }?.let { return it.key }

        favorites.values.firstOrNull { relationship ->
            relationship.peerNostrPublicKey?.let { ContactIdentityResolver.nostrPubkeyHex(it) } == targetHex ||
                relationship.peerNdrSessionPubkeyHex == targetHex
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
    @Synchronized
    fun updatePeerFavoritedUs(noisePublicKey: ByteArray, theyFavoritedUs: Boolean) {
        val keyHex = ContactIdentityResolver.noiseKeyHex(noisePublicKey)
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
    fun findNoiseKey(forNostrPubkey: String): ByteArray? {
        val targetHex = ContactIdentityResolver.nostrPubkeyHex(forNostrPubkey) ?: return null
        return favorites.values.firstOrNull { rel ->
            rel.peerNostrPublicKey?.let { stored -> ContactIdentityResolver.nostrPubkeyHex(stored) } == targetHex ||
                rel.peerNdrSessionPubkeyHex == targetHex
        }?.peerNoisePublicKey
    }

    /** Find Nostr pubkey by Noise key */
    fun findNostrPubkey(forNoiseKey: ByteArray): String? {
        val keyHex = ContactIdentityResolver.noiseKeyHex(forNoiseKey)
        return favorites[keyHex]?.peerNostrPublicKey
    }

    /** Persist the owner pubkey used to look up this peer's ratchet session. */
    fun updateNdrSessionPubkeyHex(noisePublicKey: ByteArray, peerPubkeyHex: String): Boolean {
        val normalized = ContactIdentityResolver.nostrPubkeyHex(peerPubkeyHex) ?: return false
        val keyHex = ContactIdentityResolver.noiseKeyHex(noisePublicKey)
        var oldPeerPubkeyHex: String? = null
        var originalNostrHex: String? = null
        var originalNdrHex: String? = null
        synchronized(this) {
            if (keyHex in ndrRebindsInProgress ||
                isIdentityBoundToAnotherFavorite(keyHex, normalized)
            ) return false
            val existing = favorites[keyHex] ?: return false
            if (existing.peerNdrSessionPubkeyHex == normalized) return true
            val oldPeer = effectiveNdrPeerPubkeyHex(existing)
            val isRebind = oldPeer != null &&
                !oldPeer.equals(normalized, ignoreCase = true)
            val mustRetire = isRebind &&
                !isIdentityReferencedByAnotherFavorite(keyHex, oldPeer)
            if (!mustRetire) {
                favorites[keyHex] = existing.copy(
                    peerNdrSessionPubkeyHex = normalized,
                    lastUpdated = Date()
                )
                saveFavorites()
            } else {
                ndrRebindsInProgress.add(keyHex)
                oldPeerPubkeyHex = oldPeer
                originalNostrHex = existing.peerNostrPublicKey
                    ?.let(ContactIdentityResolver::nostrPubkeyHex)
                originalNdrHex = existing.peerNdrSessionPubkeyHex
            }
        }

        val peerToRetire = oldPeerPubkeyHex
        if (peerToRetire != null) {
            if (!retireBeforeRebind(peerToRetire)) {
                synchronized(this) { ndrRebindsInProgress.remove(keyHex) }
                Log.e(TAG, "Refusing NDR session rebind before old peer is retired")
                return false
            }
            val committed = synchronized(this) {
                val current = favorites[keyHex]
                val bindingUnchanged =
                    current?.peerNostrPublicKey
                        ?.let(ContactIdentityResolver::nostrPubkeyHex) == originalNostrHex &&
                        current?.peerNdrSessionPubkeyHex == originalNdrHex
                val canCommit = current != null &&
                    bindingUnchanged &&
                    !isIdentityBoundToAnotherFavorite(keyHex, normalized)
                if (canCommit) {
                    favorites[keyHex] = current.copy(
                        peerNdrSessionPubkeyHex = normalized,
                        lastUpdated = Date()
                    )
                    saveFavorites()
                }
                ndrRebindsInProgress.remove(keyHex)
                canCommit
            }
            if (!committed) return false
        }

        notifyChanged(keyHex)
        return true
    }

    /** Resolve the best ratchet-session lookup key for this Noise identity. */
    fun findNdrSessionPubkeyHex(forNoiseKey: ByteArray): String? {
        val keyHex = ContactIdentityResolver.noiseKeyHex(forNoiseKey)
        val relationship = favorites[keyHex] ?: return null
        return relationship.peerNdrSessionPubkeyHex
            ?: relationship.peerNostrPublicKey?.let(ContactIdentityResolver::nostrPubkeyHex)
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
        synchronized(listeners) { if (!listeners.contains(listener)) listeners.add(listener) }
    }
    fun removeListener(listener: FavoritesChangeListener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    @Synchronized
    fun setNdrPeerRetirementGuard(
        guard: ((oldPeerPubkeyHex: String) -> Boolean)?
    ) {
        ndrPeerRetirementGuard = guard
    }

    private fun effectiveNdrPeerPubkeyHex(
        relationship: FavoriteRelationship
    ): String? = relationship.peerNdrSessionPubkeyHex
        ?: relationship.peerNostrPublicKey?.let(ContactIdentityResolver::nostrPubkeyHex)

    private fun relationshipWithNostrIdentity(
        existing: FavoriteRelationship?,
        noisePublicKey: ByteArray,
        normalizedNpub: String,
        clearExplicitNdrPeer: Boolean
    ): FavoriteRelationship = existing?.copy(
        peerNostrPublicKey = normalizedNpub,
        peerNdrSessionPubkeyHex =
            if (clearExplicitNdrPeer) null else existing.peerNdrSessionPubkeyHex,
        lastUpdated = Date()
    ) ?: FavoriteRelationship(
        peerNoisePublicKey = noisePublicKey,
        peerNostrPublicKey = normalizedNpub,
        peerNickname = "Unknown",
        isFavorite = false,
        theyFavoritedUs = false,
        favoritedAt = Date(),
        lastUpdated = Date()
    )

    private fun isIdentityBoundToAnotherFavorite(
        noiseKeyHex: String,
        peerPubkeyHex: String
    ): Boolean = favorites.any { (otherNoiseKeyHex, relationship) ->
        otherNoiseKeyHex != noiseKeyHex &&
            relationshipReferencesIdentity(relationship, peerPubkeyHex)
    }

    private fun isIdentityReferencedByAnotherFavorite(
        noiseKeyHex: String,
        peerPubkeyHex: String
    ): Boolean = isIdentityBoundToAnotherFavorite(noiseKeyHex, peerPubkeyHex)

    private fun relationshipReferencesIdentity(
        relationship: FavoriteRelationship,
        peerPubkeyHex: String
    ): Boolean =
        relationship.peerNdrSessionPubkeyHex
            ?.equals(peerPubkeyHex, ignoreCase = true) == true ||
            relationship.peerNostrPublicKey
                ?.let(ContactIdentityResolver::nostrPubkeyHex)
                ?.equals(peerPubkeyHex, ignoreCase = true) == true

    private fun retireBeforeRebind(oldPeerPubkeyHex: String): Boolean =
        runCatching {
            ndrPeerRetirementGuard?.invoke(oldPeerPubkeyHex) == true
        }.getOrDefault(false)

    private fun notifyChanged(noiseKeyHex: String) {
        runCatching { AppStateStore.canonicalizePrivateChats() }
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
    val peerNdrSessionPubkeyHex: String? = null,
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
                peerNdrSessionPubkeyHex = relationship.peerNdrSessionPubkeyHex,
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
            peerNdrSessionPubkeyHex = peerNdrSessionPubkeyHex,
            peerNickname = peerNickname,
            isFavorite = isFavorite,
            theyFavoritedUs = theyFavoritedUs,
            favoritedAt = Date(favoritedAt),
            lastUpdated = Date(lastUpdated)
        )
    }
}
