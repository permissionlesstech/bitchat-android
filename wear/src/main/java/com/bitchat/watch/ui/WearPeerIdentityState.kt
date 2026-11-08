package com.bitchat.watch.ui

import android.content.Context
import com.bitchat.android.favorites.FavoriteRelationship
import com.bitchat.android.favorites.FavoritesChangeListener
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.identity.scope.IdentityScoped
import com.bitchat.android.mesh.MeshPeers
import com.bitchat.watch.mesh.WearMeshService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

enum class FavoriteIndicator {
    None,
    FavoritedUs,
    Favorite
}

data class WearPeerIdentitySnapshot(
    val isFavorite: Boolean,
    val theyFavoritedUs: Boolean,
    val favoriteIndicator: FavoriteIndicator,
    val fingerprint: String?,
    val isVerified: Boolean
)

fun favoriteIndicator(
    isFavorite: Boolean,
    theyFavoritedUs: Boolean
): FavoriteIndicator = when {
    isFavorite -> FavoriteIndicator.Favorite
    theyFavoritedUs -> FavoriteIndicator.FavoritedUs
    else -> FavoriteIndicator.None
}

/**
 * Bridge between the shared identity stores and the Watch Compose UI.
 *
 * Favorites are keyed by the authenticated Noise public key, while screens navigate with the
 * short mesh peer ID. The shared persistence service resolves that mapping and notifies this
 * state whenever either side changes the relationship.
 */
@IdentityScoped
class WearPeerIdentityState @Inject constructor(
    private val favorites: FavoritesPersistenceService,
) : FavoritesChangeListener {
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    @Volatile
    private var initialized = false

    private lateinit var identityManager: SecureIdentityStateManager

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            favorites.initialize()
            identityManager = SecureIdentityStateManager(context.applicationContext)
            favorites.addListener(this)
            initialized = true
        }
        publishChange()
    }

    fun snapshot(peerID: String, mesh: MeshPeers?): WearPeerIdentitySnapshot {
        check(initialized) { "WearPeerIdentityState must be initialized by the application" }
        val relationship = relationship(peerID, mesh)
        val fingerprint = mesh?.getPeerFingerprint(peerID)
            ?: relationship?.peerNoisePublicKey?.let(identityManager::generateFingerprint)
        val isVerified = fingerprint != null &&
            identityManager.getVerifiedFingerprints().any {
                it.equals(fingerprint, ignoreCase = true)
            }
        val isFavorite = relationship?.isFavorite == true
        val theyFavoritedUs = relationship?.theyFavoritedUs == true
        return WearPeerIdentitySnapshot(
            isFavorite = isFavorite,
            theyFavoritedUs = theyFavoritedUs,
            favoriteIndicator = favoriteIndicator(isFavorite, theyFavoritedUs),
            fingerprint = fingerprint,
            isVerified = isVerified
        )
    }

    /** Needs the watch's own nickname lookup and its session-waiting favorite notification, so it takes the whole service. */
    fun setFavorite(
        peerID: String,
        isFavorite: Boolean,
        mesh: WearMeshService
    ): Boolean {
        check(initialized) { "WearPeerIdentityState must be initialized by the application" }
        val peerInfo = mesh.getPeerInfo(peerID) ?: return false
        val noisePublicKey = peerInfo.noisePublicKey ?: return false
        val nickname = mesh.getPeerNickname(peerID)
            ?: peerInfo.nickname.takeIf(String::isNotBlank)
            ?: peerID.take(8)

        favorites.updateFavoriteStatus(
            noisePublicKey = noisePublicKey,
            nickname = nickname,
            isFavorite = isFavorite
        )
        mesh.sendFavoriteNotification(peerID, isFavorite)
        return true
    }

    fun setVerified(
        peerID: String,
        verified: Boolean,
        mesh: MeshPeers?
    ): Boolean {
        check(initialized) { "WearPeerIdentityState must be initialized by the application" }
        val fingerprint = snapshot(peerID, mesh).fingerprint ?: return false
        identityManager.setVerifiedFingerprint(fingerprint.lowercase(), verified)
        publishChange()
        return true
    }

    fun myFingerprint(mesh: MeshPeers?): String? = mesh?.getIdentityFingerprint()

    override fun onFavoriteChanged(noiseKeyHex: String) {
        publishChange()
    }

    override fun onAllCleared() {
        publishChange()
    }

    private fun relationship(
        peerID: String,
        mesh: MeshPeers?
    ): FavoriteRelationship? {
        favorites.getFavoriteStatus(peerID)?.let { return it }
        val noisePublicKey = mesh?.getPeerInfo(peerID)?.noisePublicKey ?: return null
        return favorites.getFavoriteStatus(noisePublicKey)
    }

    private fun publishChange() {
        _revision.update { it + 1L }
    }
}
