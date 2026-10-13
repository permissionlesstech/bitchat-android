package com.bitchat.android.ui

import android.content.Context
import android.util.Log
import com.bitchat.android.di.ChatSessionScope
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.geohash.GeohashBookmarksStore
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.services.ConversationListPreferences
import com.bitchat.android.services.SeenMessageStore
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Inject
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The emergency wipe behind the triple tap: stop admission, erase messages,
 * keys, favourites, media, notifications and geohash state, pick a new
 * nickname, and restart the mesh under a fresh identity.
 *
 * Moved out of ChatViewModel unchanged. The order of the steps is the
 * contract: admission stops before storage is wiped, and the mesh restarts
 * only once the conversation database is proven erased.
 */
@ActivityRetainedScoped
class PanicClear @Inject constructor(
    @ApplicationContext private val context: Context,
    private val state: ChatState,
    private val sessionMesh: ChatSessionMesh,
    private val meshSource: SessionMeshSource,
    private val meshDelegate: ChatMeshDelegate,
    private val messageManager: MessageManager,
    private val channelManager: ChannelManager,
    private val privateChatManager: PrivateChatManager,
    private val dataManager: DataManager,
    private val mediaSendingManager: MediaSendingManager,
    private val notificationManager: NotificationManager,
    private val geohashSession: GeohashSession,
    private val conversationListPreferences: ConversationListPreferences,
    private val seenMessageStoreLazy: Lazy<SeenMessageStore>,
    private val locationChannelManagerLazy: Lazy<LocationChannelManager>,
    private val geohashBookmarksStoreLazy: Lazy<GeohashBookmarksStore>,
    @ChatSessionScope private val scope: CoroutineScope,
) {

    private companion object {
        const val TAG = "PanicClear"
    }

    private val mesh get() = sessionMesh.unified
    private val seenMessageStore get() = seenMessageStoreLazy.get()
    private val locationChannelManager get() = locationChannelManagerLazy.get()
    private val geohashBookmarksStore get() = geohashBookmarksStoreLazy.get()

    private var panicClearInProgress = false

    /** Wipes everything and restarts the mesh under a new identity. Ignored while one runs. */
    fun run() {
        if (panicClearInProgress) return
        panicClearInProgress = true
        scope.launch {
            try {
                performPanicClearAllData()
            } finally {
                panicClearInProgress = false
            }
        }
    }

    private suspend fun performPanicClearAllData() {
        Log.w(TAG, "🚨 PANIC MODE ACTIVATED - Clearing all sensitive data")
        try {
            locationChannelManager.disableLocationServices()
        } catch (_: Exception) { }

        // A pending one-shot downgrade confirmation must not survive panic or
        // become actionable against the fresh post-wipe identity.
        mediaSendingManager.clearPendingPrivateMediaConsent()

        // Stop all message admission before wiping storage. The AppStateStore gate also rejects
        // any transport callback already in flight until the fresh identity is ready.
        clearAllMeshServiceData()
        val conversationsCleared =
            com.bitchat.android.services.AppStateStore
                .panicClearPrivateConversations()

        // Clear all UI managers
        com.bitchat.android.services.AppStateStore.clear()
        messageManager.clearAllMessages()
        channelManager.clearAllChannels()
        privateChatManager.clearAllPrivateChats()
        dataManager.clearAllData()
        conversationListPreferences.clearAll()
        
        // Clear seen message store and MessageRouter outbox
        try {
            seenMessageStore.clear()
        } catch (_: Exception) { }
        try {
            com.bitchat.android.services.MessageRouter.tryGetInstance()?.clearAll()
        } catch (_: Exception) { }
        
        // Clear all cryptographic data
        clearAllCryptographicData()
        
        // Clear all notifications
        notificationManager.clearAllNotifications(removeConversationShortcuts = true)

        // Clear all media files
        com.bitchat.android.features.file.FileUtils.clearAllMedia(context)
        
        // Clear Nostr/geohash state, keys, connections, bookmarks, and reinitialize from scratch
        try {
            // Clear geohash bookmarks too (panic should remove everything)
            try {
                geohashBookmarksStore.clearAll()
            } catch (_: Exception) { }

            try {
                locationChannelManager.clearPersistedChannel()
            } catch (_: Exception) { }

            geohashSession.panicReset()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset Nostr/geohash: ${e.message}")
        }

        // Reset nickname
        val newNickname = "anon${Random.nextInt(1000, 9999)}"
        state.setNickname(newNickname)
        dataManager.saveNickname(newNickname)

        if (!conversationsCleared) {
            // Privacy wins over availability: keep private-message admission and transports
            // stopped if SQLite could not prove that the conversation history was erased.
            Log.e(TAG, "🚨 PANIC MODE INCOMPLETE - conversation database wipe failed")
            return
        }

        // Recreate mesh service with fresh identity
        com.bitchat.android.services.AppStateStore
            .resumePrivateConversationsAfterPanic()
        recreateMeshServiceAfterPanic()

        Log.w(TAG, "🚨 PANIC MODE COMPLETED - New identity: ${mesh.myPeerID}")
    }

    /**
     * Recreate the mesh service with a fresh identity after panic clear.
     * This ensures the new cryptographic keys are used for a new peer ID.
     */
    private fun recreateMeshServiceAfterPanic() {
        val oldPeerID = mesh.myPeerID

        // Create fresh mesh service with new identity (keys were regenerated in clearAllCryptographicData)
        val freshUnifiedMeshService = meshSource.recreate()

        // Replace the session's reference and set up the new service
        sessionMesh.replace(freshUnifiedMeshService)
        mesh.delegate = meshDelegate

        // Restart mesh operations with new identity
        mesh.startServices()
        mesh.sendBroadcastAnnounce()

        Log.d(
            TAG,
            "✅ Mesh service recreated. Old peerID: $oldPeerID, New peerID: ${mesh.myPeerID}"
        )
    }
    
    /**
     * Clear all mesh service related data
     */
    private fun clearAllMeshServiceData() {
        try {
            // Request mesh service to clear all its internal data
            mesh.clearAllInternalData()
            
            Log.d(TAG, "✅ Cleared all mesh service data")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error clearing mesh service data: ${e.message}")
        }
    }
    
    /**
     * Clear all cryptographic data including persistent identity
     */
    private fun clearAllCryptographicData() {
        try {
            // Clear encryption service persistent identity (Ed25519 signing keys)
            mesh.clearAllEncryptionData()
            
            // Clear secure identity state (if used)
            try {
                val identityManager = SecureIdentityStateManager(context)
                identityManager.clearIdentityData()
                // Also clear secure values used by FavoritesPersistenceService (favorites + peerID index)
                try {
                    identityManager.clearSecureValues("favorite_relationships", "favorite_peerid_index")
                } catch (_: Exception) { }
                Log.d(TAG, "✅ Cleared secure identity state and secure favorites store")
            } catch (e: Exception) {
                Log.d(TAG, "SecureIdentityStateManager not available or already cleared: ${e.message}")
            }

            // Clear FavoritesPersistenceService persistent relationships
            try {
                FavoritesPersistenceService.shared.clearAllFavorites()
                Log.d(TAG, "✅ Cleared FavoritesPersistenceService relationships")
            } catch (_: Exception) { }
            
            Log.d(TAG, "✅ Cleared all cryptographic data")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error clearing cryptographic data: ${e.message}")
        }
    }
}
