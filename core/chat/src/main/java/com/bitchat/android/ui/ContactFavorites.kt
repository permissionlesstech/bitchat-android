package com.bitchat.android.ui

import android.content.Context
import android.util.Log
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.ContactIdentityResolver
import com.bitchat.android.services.MessageRouter
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Inject
import javax.inject.Provider

/**
 * Favouriting a peer: the local flag, the persisted relationship, and the
 * notice sent to the peer. Moved out of ChatViewModel unchanged, so the
 * private chat and the peer list can toggle it without the ViewModel.
 */
@ActivityRetainedScoped
class ContactFavorites @Inject constructor(
    @ApplicationContext private val context: Context,
    private val state: ChatState,
    private val dataManager: DataManager,
    private val privateChatManager: PrivateChatManager,
    private val sessionMesh: ChatSessionMesh,
    private val messageRouterProvider: Provider<MessageRouter>,
) {

    private val mesh get() = sessionMesh.unified

    private companion object {
        const val TAG = "ContactFavorites"
    }

    fun isFavorite(peerID: String): Boolean = privateChatManager.isFavorite(peerID)

    fun toggle(peerID: String) {
        Log.d(TAG, "toggleFavorite called for peerID: $peerID")
        privateChatManager.toggleFavorite(peerID)

        // Persist relationship in FavoritesPersistenceService
        try {
            var noiseKey: ByteArray? = null
            var nickname: String = mesh.getPeerNicknames()[peerID] ?: peerID

            val peerInfo = mesh.getPeerInfo(peerID)
            if (peerInfo?.noisePublicKey != null) {
                noiseKey = peerInfo.noisePublicKey
                nickname = peerInfo.nickname
            } else if (ContactIdentityResolver.isNoiseKeyHex(peerID)) {
                noiseKey = ContactIdentityResolver.bytesFromHex(peerID)
                val rel = noiseKey?.let {
                    com.bitchat.android.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(it)
                }
                if (rel != null) nickname = rel.peerNickname
            } else {
                val contact = ContactDirectory.resolve(peerID)
                noiseKey = contact.noisePublicKey
                contact.displayName?.let { nickname = it }
            }

            if (noiseKey != null) {
                val identityManager = com.bitchat.android.identity.SecureIdentityStateManager(context)
                val fingerprint = identityManager.generateFingerprint(noiseKey!!)
                val isNowFavorite = dataManager.favoritePeers.contains(fingerprint)

                com.bitchat.android.favorites.FavoritesPersistenceService.shared.updateFavoriteStatus(
                    noisePublicKey = noiseKey!!,
                    nickname = nickname,
                    isFavorite = isNowFavorite
                )

                try {
                    messageRouterProvider.get().sendFavoriteNotification(peerID, isNowFavorite)
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }

        // Log current state after toggle
        logCurrentFavoriteState()
    }

    fun logCurrentFavoriteState() {
        Log.i(TAG, "=== CURRENT FAVORITE STATE ===")
        Log.i(TAG, "StateFlow favorite peers: ${state.favoritePeers.value}")
        Log.i(TAG, "DataManager favorite peers: ${dataManager.favoritePeers}")
        Log.i(TAG, "Peer fingerprints: ${privateChatManager.getAllPeerFingerprints()}")
        Log.i(TAG, "==============================")
    }
}
