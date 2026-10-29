package com.bitchat.android.ui

import android.content.Context
import android.util.Log
import com.bitchat.android.favorites.FavoriteRelationship
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.mesh.PeerInfo
import com.bitchat.android.nostr.NostrIdentityBridge
import com.bitchat.android.services.ContactDirectory
import dagger.Binds
import dagger.Lazy
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * The reads the peer list makes that are not snapshot state: favourites, cached Noise keys,
 * mesh peer info, alias resolution, verification fingerprints and the geohash identity.
 *
 * Changes behind these calls do not recompose the list (inventory Q-P14). Tests fake them; the
 * app uses [RealPeerListLookups], which forwards to exactly the calls the list made before.
 */
interface PeerListLookups {
    /** `ContactDirectory.aliasesForConversation`. */
    fun aliasesForConversation(peerOrConversationID: String): Set<String>

    /** `ContactDirectory.canonicalConversationId`. */
    fun canonicalConversationId(peerOrConversationID: String): String

    /** `ContactFavorites.isFavorite`. */
    fun isFavorite(peerID: String): Boolean

    /** `FavoritesPersistenceService.getFavoriteStatus(peerID)`. */
    fun favoriteStatus(peerID: String): FavoriteRelationship?

    /** `FavoritesPersistenceService.getOurFavorites()`. */
    fun ourFavorites(): List<FavoriteRelationship>

    /** `FavoritesPersistenceService.findNostrPubkeyForPeerID`. */
    fun nostrPubkeyForPeerID(peerID: String): String?

    /** `FavoritesPersistenceService.findNostrPubkey(forNoiseKey)`. */
    fun nostrPubkeyForNoiseKey(noisePublicKey: ByteArray): String?

    /** The mesh's current `PeerInfo` for [peerID]. */
    fun peerInfo(peerID: String): PeerInfo?

    /** `SecureIdentityStateManager.getCachedNoiseKey`: the cached Noise key hex, if any. */
    fun cachedNoiseKey(peerID: String): String?

    /** `VerificationHandler.getPeerFingerprintForDisplay`. */
    fun peerFingerprintForDisplay(peerID: String): String?

    /** `VerificationHandler.fingerprintFromNoiseBytes`. */
    fun fingerprintFromNoiseBytes(noisePublicKey: ByteArray): String

    /**
     * Our own Nostr public key hex for [geohash], as `NostrIdentityBridge.deriveIdentity`
     * returns it, or null when derivation fails (logged, as before).
     */
    fun geohashIdentityPublicKeyHex(geohash: String): String?
}

/**
 * Forwards to the singletons and session objects. The favourites are resolved on every call,
 * not captured, so an uninitialised service throws where it threw before. The identity state
 * manager is built once, with the adapter, so a failure to build it is as loud as it was in
 * composition and is not retried per row.
 */
internal class RealPeerListLookups @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val contactFavorites: ContactFavorites,
    private val verificationHandler: VerificationHandler,
    private val sessionMesh: ChatSessionMesh,
    private val contactDirectory: ContactDirectory,
    private val favorites: Lazy<FavoritesPersistenceService>,
    private val nostrIdentity: NostrIdentityBridge,
) : PeerListLookups {

    private val identityStateManager = SecureIdentityStateManager(appContext)

    override fun aliasesForConversation(peerOrConversationID: String): Set<String> =
        contactDirectory.aliasesForConversation(peerOrConversationID)

    override fun canonicalConversationId(peerOrConversationID: String): String =
        contactDirectory.canonicalConversationId(peerOrConversationID)

    override fun isFavorite(peerID: String): Boolean = contactFavorites.isFavorite(peerID)

    override fun favoriteStatus(peerID: String): FavoriteRelationship? =
        favorites.get().getFavoriteStatus(peerID)

    override fun ourFavorites(): List<FavoriteRelationship> =
        favorites.get().getOurFavorites()

    override fun nostrPubkeyForPeerID(peerID: String): String? =
        favorites.get().findNostrPubkeyForPeerID(peerID)

    override fun nostrPubkeyForNoiseKey(noisePublicKey: ByteArray): String? =
        favorites.get().findNostrPubkey(noisePublicKey)

    override fun peerInfo(peerID: String): PeerInfo? = sessionMesh.unified.getPeerInfo(peerID)

    override fun cachedNoiseKey(peerID: String): String? =
        identityStateManager.getCachedNoiseKey(peerID)

    override fun peerFingerprintForDisplay(peerID: String): String? =
        verificationHandler.getPeerFingerprintForDisplay(peerID)

    override fun fingerprintFromNoiseBytes(noisePublicKey: ByteArray): String =
        verificationHandler.fingerprintFromNoiseBytes(noisePublicKey)

    override fun geohashIdentityPublicKeyHex(geohash: String): String? =
        try {
            nostrIdentity.deriveIdentity(
                forGeohash = geohash,
                context = appContext
            ).publicKeyHex
        } catch (e: Exception) {
            Log.e("GeohashPeopleList", "Failed to derive identity: ${e.message}")
            null
        }
}

@Module
@InstallIn(ViewModelComponent::class)
internal abstract class PeerListLookupsModule {
    @Binds
    abstract fun bindPeerListLookups(impl: RealPeerListLookups): PeerListLookups
}
