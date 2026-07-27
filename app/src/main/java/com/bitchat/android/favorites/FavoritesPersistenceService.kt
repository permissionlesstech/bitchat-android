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
    val ndrRequired: Boolean = false,
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
        if (ndrRequired != other.ndrRequired) return false
        if (peerNickname != other.peerNickname) return false
        if (isFavorite != other.isFavorite) return false
        if (theyFavoritedUs != other.theyFavoritedUs) return false

        return true
    }

    override fun hashCode(): Int {
        var result = peerNoisePublicKey.contentHashCode()
        result = 31 * result + (peerNostrPublicKey?.hashCode() ?: 0)
        result = 31 * result + (peerNdrSessionPubkeyHex?.hashCode() ?: 0)
        result = 31 * result + ndrRequired.hashCode()
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
        internal const val FAVORITES_KEY = "favorite_relationships"
        internal const val PEERID_INDEX_KEY = "favorite_peerid_index"
        internal const val NDR_REBIND_JOURNAL_KEY = "favorite_ndr_rebind_v1"

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
    private var pendingNdrRebind: FavoriteNdrRebindJournal? = null
    private var ndrRebindJournalCorrupt = false
    private var favoritesStorageUnreadable = false

    init {
        loadFavorites()
        loadNdrRebindJournal()
        loadPeerIdIndex()
    }

    /** Get favorite status for Noise public key */
    @Synchronized
    fun getFavoriteStatus(noisePublicKey: ByteArray): FavoriteRelationship? {
        val keyHex = ContactIdentityResolver.noiseKeyHex(noisePublicKey)
        return favoriteForReadLocked(keyHex)
    }

    /** Get favorite status for a mesh peer ID or full Noise public key hex. */
    @Synchronized
    fun getFavoriteStatus(peerID: String): FavoriteRelationship? {
        val pid = peerID.trim().lowercase()

        if (ContactIdentityResolver.isNoiseKeyHex(pid)) {
            return favoriteForReadLocked(pid)
        }

        ContactIdentityResolver.fingerprintFromContactConversationId(pid)?.let { fingerprint ->
            return favorites.entries.firstNotNullOfOrNull { (keyHex, _) ->
                val relationship = favoriteForReadLocked(keyHex)
                    ?: return@firstNotNullOfOrNull null
                ContactIdentityResolver.fingerprintHex(relationship.peerNoisePublicKey)
                    .equals(fingerprint, ignoreCase = true)
                    .takeIf { it }
                    ?.let { relationship }
            }
        }

        if (ContactIdentityResolver.isMeshPeerId(pid)) {
            peerIdIndex[pid]?.let { indexedNpub ->
                findNoiseKey(indexedNpub)?.let { return getFavoriteStatus(it) }
            }
            return favorites.entries.firstNotNullOfOrNull { (keyHex, _) ->
                val relationship = favoriteForReadLocked(keyHex)
                    ?: return@firstNotNullOfOrNull null
                relationship.takeIf {
                    ContactIdentityResolver.peerIdForNoiseKey(it.peerNoisePublicKey) == pid
                }
            }
        }

        return null
    }

    /** Update Nostr public key for a peer (indexed by Noise key) */
    fun updateNostrPublicKey(noisePublicKey: ByteArray, nostrPubkey: String): Boolean {
        val keyHex = ContactIdentityResolver.noiseKeyHex(noisePublicKey)
        val normalizedHex = ContactIdentityResolver.nostrPubkeyHex(nostrPubkey) ?: return false
        val normalizedNpub = ContactIdentityResolver.npubFromHex(normalizedHex) ?: return false
        recoverPendingNdrRebind()
        var journal: FavoriteNdrRebindJournal? = null
        val committedWithoutRetirement = synchronized(this) {
            if (ndrRebindJournalCorrupt ||
                favoritesStorageUnreadable ||
                pendingNdrRebind != null ||
                ndrRebindsInProgress.isNotEmpty() ||
                isIdentityBoundToAnotherFavorite(keyHex, normalizedHex)
            ) return false
            val existing = favorites[keyHex]
            val oldPeer = existing?.let(::effectiveNdrPeerPubkeyHex)
            val isRebind = oldPeer != null &&
                !oldPeer.equals(normalizedHex, ignoreCase = true)
            val wasNdrRequired = existing?.ndrRequired == true ||
                existing?.peerNdrSessionPubkeyHex != null
            val requiresRetirement = wasNdrRequired && isRebind
            val updated = relationshipWithNostrIdentity(
                existing = existing,
                noisePublicKey = noisePublicKey,
                normalizedNpub = normalizedNpub,
                clearExplicitNdrPeer = requiresRetirement,
                requireNdr = wasNdrRequired
            )
            val mustRetire = requiresRetirement &&
                !isIdentityReferencedByAnotherFavorite(keyHex, requireNotNull(oldPeer))
            if (!mustRetire) {
                commitFavoriteUpdateLocked(keyHex, updated)
            } else {
                journal = FavoriteNdrRebindJournal(
                    noiseKeyHex = keyHex,
                    oldPeerPubkeyHex = requireNotNull(oldPeer),
                    expectedNostrPubkeyHex = existing.peerNostrPublicKey
                        ?.let(ContactIdentityResolver::nostrPubkeyHex),
                    expectedNdrSessionPubkeyHex = existing.peerNdrSessionPubkeyHex,
                    expectedNdrRequired = true,
                    targetNostrPubkeyHex = normalizedHex,
                    targetNdrSessionPubkeyHex = null,
                    targetNdrRequired = true,
                    retireOldPeer = true
                )
                beginNdrRebindLocked(requireNotNull(journal))
                false
            }
        }

        val pendingJournal = journal
        val committed = if (pendingJournal == null) {
            committedWithoutRetirement
        } else {
            completePendingNdrRebind(pendingJournal)
        }
        if (!committed) return false

        notifyChanged(keyHex)
        Log.d(TAG, "Updated Nostr pubkey association for ${keyHex.take(16)}...")
        return true
    }


    /** Update Nostr pubkey for a specific mesh peerID. */
    @Synchronized
    fun updateNostrPublicKeyForPeerID(peerID: String, nostrPubkey: String) {
        if (ndrRebindJournalCorrupt ||
            favoritesStorageUnreadable ||
            pendingNdrRebind != null
        ) return
        val pid = peerID.trim().lowercase()
        val normalizedNpub = ContactIdentityResolver.nostrPubkeyHex(nostrPubkey)
            ?.let { ContactIdentityResolver.npubFromHex(it) }
            ?: nostrPubkey
        if (ContactIdentityResolver.isMeshPeerId(pid)) {
            val snapshot = peerIdIndex.toMutableMap().apply {
                this[pid] = normalizedNpub
            }
            if (commitPeerIdIndexSnapshotLocked(snapshot)) {
                peerIdIndex.clear()
                peerIdIndex.putAll(snapshot)
                Log.d(TAG, "Indexed npub for peerID ${pid.take(8)}…")
            }
        } else {
            Log.w(TAG, "updateNostrPublicKeyForPeerID called with non-16hex peerID: $peerID")
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

        favorites.entries.firstNotNullOfOrNull { (keyHex, _) ->
            val relationship = favoriteForReadLocked(keyHex)
                ?: return@firstNotNullOfOrNull null
            relationship.takeIf {
                it.peerNostrPublicKey
                    ?.let(ContactIdentityResolver::nostrPubkeyHex) == targetHex ||
                    it.peerNdrSessionPubkeyHex == targetHex
            }
        }?.let { relationship ->
            return ContactIdentityResolver.peerIdForNoiseKey(relationship.peerNoisePublicKey)
        }

        return null
    }

    /** Update favorite status */
    @Synchronized
    fun updateFavoriteStatus(noisePublicKey: ByteArray, nickname: String, isFavorite: Boolean) {
        val keyHex = ContactIdentityResolver.noiseKeyHex(noisePublicKey)
        if (ndrRebindJournalCorrupt ||
            favoritesStorageUnreadable ||
            pendingNdrRebind?.noiseKeyHex == keyHex
        ) return

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

        if (!commitFavoriteUpdateLocked(keyHex, updated)) return
        notifyChanged(keyHex)

        Log.d(TAG, "Updated favorite status for $nickname: $isFavorite")
    }

    /** Update peer favorited-us flag */
    @Synchronized
    fun updatePeerFavoritedUs(noisePublicKey: ByteArray, theyFavoritedUs: Boolean) {
        val keyHex = ContactIdentityResolver.noiseKeyHex(noisePublicKey)
        if (ndrRebindJournalCorrupt ||
            favoritesStorageUnreadable ||
            pendingNdrRebind?.noiseKeyHex == keyHex
        ) return
        val existing = favorites[keyHex]

        if (existing != null) {
            val updated = existing.copy(
                theyFavoritedUs = theyFavoritedUs,
                lastUpdated = Date()
            )
            if (!commitFavoriteUpdateLocked(keyHex, updated)) return
            notifyChanged(keyHex)

            Log.d(TAG, "Updated peer favorited us for ${keyHex.take(16)}...: $theyFavoritedUs")
        }
    }

    @Synchronized
    fun getMutualFavorites(): List<FavoriteRelationship> =
        favoritesForReadLocked().filter { it.isMutual }

    @Synchronized
    fun getOurFavorites(): List<FavoriteRelationship> =
        favoritesForReadLocked().filter { it.isFavorite }

    @Synchronized
    fun getAllRelationships(): List<FavoriteRelationship> = favoritesForReadLocked()

    /**
     * Clear contact bindings only after the caller has durably wiped native NDR state.
     */
    @Synchronized
    fun clearAllFavoritesAfterNdrReset(): Boolean {
        val committed = runCatching {
            stateManager.commitSecureValuesSynchronously(
                removals = setOf(
                    FAVORITES_KEY,
                    PEERID_INDEX_KEY,
                    NDR_REBIND_JOURNAL_KEY
                )
            )
        }.getOrDefault(false)
        if (!committed) return false

        favorites.clear()
        peerIdIndex.clear()
        pendingNdrRebind = null
        ndrRebindsInProgress.clear()
        ndrRebindJournalCorrupt = false
        favoritesStorageUnreadable = false
        Log.i(TAG, "Cleared all favorites")
        notifyAllCleared()
        return true
    }

    /** Find Noise key by Nostr pubkey */
    @Synchronized
    fun findNoiseKey(forNostrPubkey: String): ByteArray? {
        val targetHex = ContactIdentityResolver.nostrPubkeyHex(forNostrPubkey) ?: return null
        return favorites.entries.firstNotNullOfOrNull { (keyHex, _) ->
            val relationship = favoriteForReadLocked(keyHex)
                ?: return@firstNotNullOfOrNull null
            relationship.peerNoisePublicKey.takeIf {
                relationship.peerNostrPublicKey
                    ?.let(ContactIdentityResolver::nostrPubkeyHex) == targetHex ||
                    relationship.peerNdrSessionPubkeyHex == targetHex
            }
        }
    }

    /** Find Nostr pubkey by Noise key */
    @Synchronized
    fun findNostrPubkey(forNoiseKey: ByteArray): String? {
        val keyHex = ContactIdentityResolver.noiseKeyHex(forNoiseKey)
        return favoriteForReadLocked(keyHex)?.peerNostrPublicKey
    }

    /** Persist the owner pubkey used to look up this peer's ratchet session. */
    fun updateNdrSessionPubkeyHex(noisePublicKey: ByteArray, peerPubkeyHex: String): Boolean {
        val normalized = ContactIdentityResolver.nostrPubkeyHex(peerPubkeyHex) ?: return false
        val keyHex = ContactIdentityResolver.noiseKeyHex(noisePublicKey)
        recoverPendingNdrRebind()
        var journal: FavoriteNdrRebindJournal? = null
        val committedWithoutRetirement = synchronized(this) {
            if (ndrRebindJournalCorrupt ||
                favoritesStorageUnreadable ||
                pendingNdrRebind != null ||
                ndrRebindsInProgress.isNotEmpty() ||
                isIdentityBoundToAnotherFavorite(keyHex, normalized)
            ) return false
            val existing = favorites[keyHex] ?: return false
            if (existing.peerNdrSessionPubkeyHex == normalized && existing.ndrRequired) {
                return true
            }
            val oldPeer = effectiveNdrPeerPubkeyHex(existing)
            val isRebind = oldPeer != null &&
                !oldPeer.equals(normalized, ignoreCase = true)
            val wasNdrRequired = existing.ndrRequired ||
                existing.peerNdrSessionPubkeyHex != null
            val requiresRetirement = wasNdrRequired && isRebind
            val updated = existing.copy(
                peerNdrSessionPubkeyHex = normalized,
                ndrRequired = true,
                lastUpdated = Date()
            )
            val mustRetire = requiresRetirement &&
                !isIdentityReferencedByAnotherFavorite(keyHex, requireNotNull(oldPeer))
            val needsDurablePin = !existing.ndrRequired
            if (!mustRetire && (!needsDurablePin || oldPeer == null)) {
                val committed = commitFavoriteUpdateLocked(keyHex, updated)
                if (!committed && needsDurablePin) {
                    favoritesStorageUnreadable = true
                }
                committed
            } else {
                journal = FavoriteNdrRebindJournal(
                    noiseKeyHex = keyHex,
                    oldPeerPubkeyHex = requireNotNull(oldPeer),
                    expectedNostrPubkeyHex = existing.peerNostrPublicKey
                        ?.let(ContactIdentityResolver::nostrPubkeyHex),
                    expectedNdrSessionPubkeyHex = existing.peerNdrSessionPubkeyHex,
                    expectedNdrRequired = existing.ndrRequired,
                    targetNostrPubkeyHex = existing.peerNostrPublicKey
                        ?.let(ContactIdentityResolver::nostrPubkeyHex),
                    targetNdrSessionPubkeyHex = normalized,
                    targetNdrRequired = true,
                    retireOldPeer = mustRetire
                )
                beginNdrRebindLocked(requireNotNull(journal))
                false
            }
        }

        val pendingJournal = journal
        val committed = if (pendingJournal == null) {
            committedWithoutRetirement
        } else {
            completePendingNdrRebind(pendingJournal)
        }
        if (!committed) return false

        notifyChanged(keyHex)
        return true
    }

    /** Resolve the best ratchet-session lookup key for this Noise identity. */
    @Synchronized
    fun findNdrSessionPubkeyHex(forNoiseKey: ByteArray): String? {
        val keyHex = ContactIdentityResolver.noiseKeyHex(forNoiseKey)
        pendingNdrRebind
            ?.takeIf { it.noiseKeyHex == keyHex }
            ?.targetEffectivePeerPubkeyHex()
            ?.let { return it }
        val relationship = favorites[keyHex] ?: return null
        return relationship.peerNdrSessionPubkeyHex
            ?: relationship.peerNostrPublicKey?.let(ContactIdentityResolver::nostrPubkeyHex)
    }

    @Synchronized
    fun isNdrRequired(forNoiseKey: ByteArray): Boolean {
        val keyHex = ContactIdentityResolver.noiseKeyHex(forNoiseKey)
        return favorites[keyHex]?.ndrRequired == true ||
            pendingNdrRebind?.noiseKeyHex == keyHex
    }

    @Synchronized
    fun isNdrRebindBlocked(forNoiseKey: ByteArray): Boolean {
        val keyHex = ContactIdentityResolver.noiseKeyHex(forNoiseKey)
        return ndrRebindJournalCorrupt ||
            favoritesStorageUnreadable ||
            pendingNdrRebind?.noiseKeyHex == keyHex
    }

    @Synchronized
    fun isNdrProtectionStateReadable(): Boolean =
        !ndrRebindJournalCorrupt && !favoritesStorageUnreadable

    /**
     * Stored-only binding used for authenticated NDR routes.
     *
     * A journal target is not authorized until the target favorite itself is durable.
     */
    @Synchronized
    fun getStoredFavoriteForNdrRoute(
        noisePublicKey: ByteArray
    ): FavoriteRelationship? {
        if (ndrRebindJournalCorrupt || favoritesStorageUnreadable) return null
        val keyHex = ContactIdentityResolver.noiseKeyHex(noisePublicKey)
        val relationship = favorites[keyHex] ?: return null
        val journal = pendingNdrRebind
        return if (journal?.noiseKeyHex == keyHex) {
            relationship.takeIf(journal::matchesTargetBinding)
        } else {
            relationship
        }
    }

    /**
     * Accept relay-delivered NDR content only from a currently bound, unquarantined peer.
     */
    @Synchronized
    fun isCurrentNdrPeerAuthorized(peerPubkeyHex: String): Boolean {
        val normalized = ContactIdentityResolver.nostrPubkeyHex(peerPubkeyHex) ?: return false
        if (ndrRebindJournalCorrupt || favoritesStorageUnreadable) return false
        return favorites.entries.any { (keyHex, _) ->
            val noiseKey = favorites[keyHex]?.peerNoisePublicKey
                ?: return@any false
            getStoredFavoriteForNdrRoute(noiseKey)
                ?.let(::effectiveNdrPeerPubkeyHex)
                ?.equals(normalized, ignoreCase = true) == true
        }
    }

    /**
     * Legacy kind-1059 is allowed only before a contact has a durable NDR pin.
     */
    @Synchronized
    fun isLegacyNostrInboundAllowed(peerPubkeyHex: String): Boolean {
        val normalized = ContactIdentityResolver.nostrPubkeyHex(peerPubkeyHex) ?: return false
        if (ndrRebindJournalCorrupt || favoritesStorageUnreadable) return false
        val journal = pendingNdrRebind
        if (journal != null && normalized in journal.quarantinedIdentityPubkeys()) {
            return false
        }
        return favorites.entries.none { (keyHex, relationship) ->
            keyHex != journal?.noiseKeyHex &&
                relationship.ndrRequired &&
                relationshipReferencesIdentity(relationship, normalized)
        }
    }

    // MARK: - Persistence

    private fun favoriteForReadLocked(keyHex: String): FavoriteRelationship? {
        val relationship = favorites[keyHex] ?: return null
        val journal = pendingNdrRebind
        return if (journal?.noiseKeyHex == keyHex) {
            journal.applyTarget(relationship)
        } else {
            relationship
        }
    }

    private fun favoritesForReadLocked(): List<FavoriteRelationship> =
        favorites.keys.mapNotNull(::favoriteForReadLocked)

    private fun commitFavoriteUpdateLocked(
        keyHex: String,
        relationship: FavoriteRelationship
    ): Boolean {
        val snapshot = favorites.toMutableMap()
        snapshot[keyHex] = relationship
        if (!commitFavoritesSnapshotLocked(snapshot)) return false
        favorites.clear()
        favorites.putAll(snapshot)
        return true
    }

    private fun commitFavoritesSnapshotLocked(
        snapshot: Map<String, FavoriteRelationship>
    ): Boolean = runCatching {
        stateManager.commitSecureValuesSynchronously(
            values = mapOf(FAVORITES_KEY to favoritesJson(snapshot))
        )
    }.getOrDefault(false)

    private fun commitPeerIdIndexSnapshotLocked(
        snapshot: Map<String, String>
    ): Boolean = runCatching {
        stateManager.commitSecureValuesSynchronously(
            values = mapOf(PEERID_INDEX_KEY to gson.toJson(snapshot))
        )
    }.getOrDefault(false)

    private fun favoritesJson(
        snapshot: Map<String, FavoriteRelationship>
    ): String {
        val data = snapshot.mapValues { (_, relationship) ->
            FavoriteRelationshipData.fromFavoriteRelationship(relationship)
        }
        return gson.toJson(data)
    }

    private fun beginNdrRebindLocked(journal: FavoriteNdrRebindJournal): Boolean {
        if (pendingNdrRebind != null ||
            ndrRebindJournalCorrupt ||
            favoritesStorageUnreadable
        ) return false
        if (!journal.isValid()) return false
        val journalJson = gson.toJson(journal)
        val committed = runCatching {
            stateManager.commitSecureValuesSynchronously(
                values = mapOf(NDR_REBIND_JOURNAL_KEY to journalJson)
            )
        }.getOrDefault(false)
        if (!committed) {
            favoritesStorageUnreadable = true
            return false
        }
        pendingNdrRebind = journal
        ndrRebindsInProgress.add(journal.noiseKeyHex)
        return true
    }

    private fun completePendingNdrRebind(
        expectedJournal: FavoriteNdrRebindJournal
    ): Boolean {
        val ownsCurrentBinding = synchronized(this) {
            val journal = pendingNdrRebind
            if (journal != expectedJournal ||
                ndrRebindJournalCorrupt ||
                favoritesStorageUnreadable
            ) {
                false
            } else {
                val current = favorites[journal.noiseKeyHex]
                val currentMatchesExpected = current != null &&
                    journal.matchesExpectedBinding(current)
                val currentMatchesTarget = current != null &&
                    journal.matchesTargetBinding(current)
                val targetCollides = journal.targetIdentityPubkeys().any { target ->
                    isIdentityBoundToAnotherFavorite(journal.noiseKeyHex, target)
                }
                if (current == null ||
                    (!currentMatchesExpected && !currentMatchesTarget) ||
                    targetCollides
                ) {
                    ndrRebindJournalCorrupt = true
                    false
                } else {
                    true
                }
            }
        }
        if (!ownsCurrentBinding) return false

        if (expectedJournal.retireOldPeer &&
            !retireBeforeRebind(expectedJournal.oldPeerPubkeyHex)
        ) {
            Log.e(TAG, "NDR rebind remains quarantined because old peer retirement failed")
            return false
        }

        return synchronized(this) {
            if (pendingNdrRebind != expectedJournal ||
                ndrRebindJournalCorrupt ||
                favoritesStorageUnreadable
            ) {
                false
            } else {
                val current = favorites[expectedJournal.noiseKeyHex]
                val currentMatchesExpected = current != null &&
                    expectedJournal.matchesExpectedBinding(current)
                val currentMatchesTarget = current != null &&
                    expectedJournal.matchesTargetBinding(current)
                val targetCollides = expectedJournal.targetIdentityPubkeys().any { target ->
                    isIdentityBoundToAnotherFavorite(
                        expectedJournal.noiseKeyHex,
                        target
                    )
                }
                if (current == null ||
                    (!currentMatchesExpected && !currentMatchesTarget) ||
                    targetCollides
                ) {
                    ndrRebindJournalCorrupt = true
                    false
                } else {
                    val targetCommitted = currentMatchesTarget ||
                        commitFavoriteUpdateLocked(
                            expectedJournal.noiseKeyHex,
                            expectedJournal.applyTarget(current)
                        )
                    val targetVerified = targetCommitted &&
                        favorites[expectedJournal.noiseKeyHex]
                            ?.let(expectedJournal::matchesTargetBinding) == true
                    if (!targetVerified) {
                        false
                    } else {
                        val cleared = runCatching {
                            stateManager.commitSecureValuesSynchronously(
                                removals = setOf(NDR_REBIND_JOURNAL_KEY)
                            )
                        }.getOrDefault(false)
                        if (cleared) {
                            pendingNdrRebind = null
                            ndrRebindsInProgress.remove(expectedJournal.noiseKeyHex)
                        }
                        cleared
                    }
                }
            }
        }
    }

    private fun recoverPendingNdrRebind(): Boolean {
        val journal = synchronized(this) {
            if (ndrRebindJournalCorrupt || favoritesStorageUnreadable) return false
            pendingNdrRebind
        } ?: return true
        val recovered = completePendingNdrRebind(journal)
        if (recovered) notifyChanged(journal.noiseKeyHex)
        return recovered
    }

    private fun loadFavorites() {
        try {
            val favoritesJson = stateManager.getSecureValue(FAVORITES_KEY)
            if (favoritesJson != null) {
                val type = object : TypeToken<Map<String, FavoriteRelationshipData>>() {}.type
                val data: Map<String, FavoriteRelationshipData> = gson.fromJson(favoritesJson, type)

                favorites.clear()
                var needsNdrPinMigration = false
                data.forEach { (key, relationshipData) ->
                    val relationship = relationshipData.toFavoriteRelationship()
                    favorites[key] = relationship
                    val storedNdrPeer = relationshipData.peerNdrSessionPubkeyHex
                    if (storedNdrPeer != null &&
                        relationship.peerNdrSessionPubkeyHex == null
                    ) {
                        favoritesStorageUnreadable = true
                    } else if (storedNdrPeer != null &&
                        (!relationshipData.ndrRequired ||
                            storedNdrPeer != relationship.peerNdrSessionPubkeyHex)
                    ) {
                        needsNdrPinMigration = true
                    }
                }
                if (needsNdrPinMigration &&
                    !commitFavoritesSnapshotLocked(favorites)
                ) {
                    favoritesStorageUnreadable = true
                    Log.e(TAG, "Failed to durably migrate existing NDR downgrade pins")
                }
                Log.d(TAG, "Loaded ${favorites.size} favorite relationships")
            }
        } catch (e: Exception) {
            favoritesStorageUnreadable = true
            Log.e(TAG, "Failed to load favorites: ${e.message}")
        }
    }

    private fun loadNdrRebindJournal() {
        val journalJson = runCatching {
            stateManager.getSecureValue(NDR_REBIND_JOURNAL_KEY)
        }.getOrElse {
            ndrRebindJournalCorrupt = true
            Log.e(TAG, "Failed to read NDR rebind journal")
            return
        } ?: return

        val journal = runCatching {
            gson.fromJson(journalJson, FavoriteNdrRebindJournal::class.java)
        }.getOrNull()
        if (journal == null || !journal.isValid()) {
            ndrRebindJournalCorrupt = true
            Log.e(TAG, "Invalid NDR rebind journal; keeping transport fail-closed")
            return
        }
        pendingNdrRebind = journal
        ndrRebindsInProgress.add(journal.noiseKeyHex)
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

    // MARK: - Listeners
    fun addListener(listener: FavoritesChangeListener) {
        synchronized(listeners) { if (!listeners.contains(listener)) listeners.add(listener) }
    }
    fun removeListener(listener: FavoritesChangeListener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    fun setNdrPeerRetirementGuard(
        guard: ((oldPeerPubkeyHex: String) -> Boolean)?
    ) {
        synchronized(this) {
            ndrPeerRetirementGuard = guard
        }
        if (guard != null) recoverPendingNdrRebind()
    }

    private fun effectiveNdrPeerPubkeyHex(
        relationship: FavoriteRelationship
    ): String? = relationship.peerNdrSessionPubkeyHex
        ?: relationship.peerNostrPublicKey?.let(ContactIdentityResolver::nostrPubkeyHex)

    private fun relationshipWithNostrIdentity(
        existing: FavoriteRelationship?,
        noisePublicKey: ByteArray,
        normalizedNpub: String,
        clearExplicitNdrPeer: Boolean,
        requireNdr: Boolean
    ): FavoriteRelationship = existing?.copy(
        peerNostrPublicKey = normalizedNpub,
        peerNdrSessionPubkeyHex =
            if (clearExplicitNdrPeer) null else existing.peerNdrSessionPubkeyHex,
        ndrRequired = requireNdr,
        lastUpdated = Date()
    ) ?: FavoriteRelationship(
        peerNoisePublicKey = noisePublicKey,
        peerNostrPublicKey = normalizedNpub,
        ndrRequired = requireNdr,
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

    private fun retireBeforeRebind(oldPeerPubkeyHex: String): Boolean {
        val guard = synchronized(this) { ndrPeerRetirementGuard } ?: return false
        return runCatching { guard(oldPeerPubkeyHex) }.getOrDefault(false)
    }

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
    val ndrRequired: Boolean = false,
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
                ndrRequired = relationship.ndrRequired,
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
        val normalizedNdrSessionPubkeyHex = peerNdrSessionPubkeyHex
            ?.let(ContactIdentityResolver::nostrPubkeyHex)
        return FavoriteRelationship(
            peerNoisePublicKey = noiseKeyBytes,
            peerNostrPublicKey = peerNostrPublicKey,
            peerNdrSessionPubkeyHex = normalizedNdrSessionPubkeyHex,
            ndrRequired = ndrRequired || normalizedNdrSessionPubkeyHex != null,
            peerNickname = peerNickname,
            isFavorite = isFavorite,
            theyFavoritedUs = theyFavoritedUs,
            favoritedAt = Date(favoritedAt),
            lastUpdated = Date(lastUpdated)
        )
    }
}

private data class FavoriteNdrRebindJournal(
    val version: Int = 1,
    val noiseKeyHex: String,
    val oldPeerPubkeyHex: String,
    val expectedNostrPubkeyHex: String?,
    val expectedNdrSessionPubkeyHex: String?,
    val expectedNdrRequired: Boolean,
    val targetNostrPubkeyHex: String?,
    val targetNdrSessionPubkeyHex: String?,
    val targetNdrRequired: Boolean,
    val retireOldPeer: Boolean
) {
    fun isValid(): Boolean {
        val isPubkey: (String) -> Boolean = {
            it.length == 64 && it.all { character -> character in "0123456789abcdef" }
        }
        return version == 1 &&
            isPubkey(noiseKeyHex) &&
            isPubkey(oldPeerPubkeyHex) &&
            listOfNotNull(
                expectedNostrPubkeyHex,
                expectedNdrSessionPubkeyHex,
                targetNostrPubkeyHex,
                targetNdrSessionPubkeyHex
            ).all(isPubkey) &&
            expectedEffectivePeerPubkeyHex() == oldPeerPubkeyHex &&
            targetEffectivePeerPubkeyHex() != null &&
            targetNdrRequired &&
            (if (retireOldPeer) {
                (expectedNdrRequired || expectedNdrSessionPubkeyHex != null) &&
                    targetEffectivePeerPubkeyHex() != oldPeerPubkeyHex
            } else {
                !expectedNdrRequired && targetNdrSessionPubkeyHex != null
            })
    }

    private fun expectedEffectivePeerPubkeyHex(): String? =
        expectedNdrSessionPubkeyHex ?: expectedNostrPubkeyHex

    fun targetEffectivePeerPubkeyHex(): String? =
        targetNdrSessionPubkeyHex ?: targetNostrPubkeyHex

    fun targetIdentityPubkeys(): Set<String> =
        listOfNotNull(targetNostrPubkeyHex, targetNdrSessionPubkeyHex).toSet()

    fun quarantinedIdentityPubkeys(): Set<String> =
        buildSet {
            add(oldPeerPubkeyHex)
            addAll(
                listOfNotNull(
                    expectedNostrPubkeyHex,
                    expectedNdrSessionPubkeyHex,
                    targetNostrPubkeyHex,
                    targetNdrSessionPubkeyHex
                )
            )
        }

    fun matchesExpectedBinding(relationship: FavoriteRelationship): Boolean =
        relationship.peerNostrPublicKey
            ?.let(ContactIdentityResolver::nostrPubkeyHex) == expectedNostrPubkeyHex &&
            relationship.peerNdrSessionPubkeyHex == expectedNdrSessionPubkeyHex &&
            relationship.ndrRequired == expectedNdrRequired

    fun matchesTargetBinding(relationship: FavoriteRelationship): Boolean =
        relationship.peerNostrPublicKey
            ?.let(ContactIdentityResolver::nostrPubkeyHex) == targetNostrPubkeyHex &&
            relationship.peerNdrSessionPubkeyHex == targetNdrSessionPubkeyHex &&
            relationship.ndrRequired == targetNdrRequired

    fun applyTarget(relationship: FavoriteRelationship): FavoriteRelationship =
        relationship.copy(
            peerNostrPublicKey = targetNostrPubkeyHex
                ?.let(ContactIdentityResolver::npubFromHex),
            peerNdrSessionPubkeyHex = targetNdrSessionPubkeyHex,
            ndrRequired = targetNdrRequired,
            lastUpdated = Date()
        )
}
