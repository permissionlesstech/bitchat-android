package com.bitchat.android.ui

import com.bitchat.android.identity.scope.CurrentIdentity
import com.bitchat.android.identity.scope.IdentityHolder
import com.bitchat.android.identity.scope.IdentityStartup
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.spam.SpamFilter
import com.bitchat.android.services.AppStateStore
import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.bitchat.android.di.CurrentSession
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.geohash.GeohashBookmarksStore
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.core.chat.R
import com.bitchat.android.features.file.FileUtils
import com.bitchat.android.services.ConversationListPreferences
import com.bitchat.android.services.MessageRouterHolder
import com.bitchat.android.services.SeenMessageStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Inject
import javax.inject.Provider
import kotlin.random.Random

/**
 * The emergency wipe behind the triple tap. It stops admission, blanks the screen, erases messages,
 * keys, favourites, media, notifications and geohash state, picks a new nickname, and then ends the
 * identity and builds a new one, so what was only in memory is gone without being listed here.
 *
 * The order of the steps is the contract: admission stops before storage is wiped, and a new identity
 * exists only once the conversation database is proven erased. The app keeps running throughout: same
 * Activity, same process. Screens follow the new session through [ChatSessionHolder.generation].
 *
 * Runs on [PanicRunner], so ending the session or leaving the app cannot stop it, and on the main
 * thread like the cleanups it triggers.
 */
@ActivityRetainedScoped
class PanicClear @Inject constructor(
    @ApplicationContext private val context: Context,
    private val runner: PanicRunner,
    private val identityHolder: IdentityHolder,
    private val identityStartup: IdentityStartup,
    private val sessionHolder: ChatSessionHolder,
    private val blankFrame: BlankFrame,
    @CurrentSession private val stateProvider: Provider<ChatState>,
    @CurrentSession private val sessionMeshProvider: Provider<ChatSessionMesh>,
    @CurrentSession private val meshDelegateProvider: Provider<ChatMeshDelegate>,
    @CurrentSession private val messageManagerProvider: Provider<MessageManager>,
    @CurrentSession private val channelManagerProvider: Provider<ChannelManager>,
    @CurrentSession private val privateChatManagerProvider: Provider<PrivateChatManager>,
    @CurrentSession private val dataManagerProvider: Provider<DataManager>,
    @CurrentSession private val mediaSendingManagerProvider: Provider<MediaSendingManager>,
    @CurrentSession private val notificationManagerProvider: Provider<NotificationManager>,
    @CurrentSession private val geohashSessionProvider: Provider<GeohashSession>,
    @CurrentSession private val secureIdentityProvider: Provider<SecureIdentityStateManager>,
    @CurrentSession private val sessionStartupProvider: Provider<ChatSessionStartup>,
    @CurrentIdentity private val meshProvider: Provider<MeshService>,
    @CurrentIdentity private val messageRouterHolder: Provider<MessageRouterHolder>,
    @CurrentIdentity private val conversationListPreferencesProvider: Provider<ConversationListPreferences>,
    @CurrentIdentity @get:VisibleForTesting val seenMessageStoreProvider: Provider<SeenMessageStore>,
    @CurrentIdentity private val locationChannelManagerProvider: Provider<LocationChannelManager>,
    @CurrentIdentity private val geohashBookmarksStoreProvider: Provider<GeohashBookmarksStore>,
    @CurrentIdentity private val appStateStoreProvider: Provider<AppStateStore>,
    @CurrentIdentity private val spamFilterProvider: Provider<SpamFilter>,
    @CurrentIdentity @get:VisibleForTesting val favorites: Provider<FavoritesPersistenceService>,
) {

    private companion object {
        const val TAG = "PanicClear"
    }

    /** The current identity's store. */
    @get:VisibleForTesting
    val appStateStore: AppStateStore get() = appStateStoreProvider.get()

    @get:VisibleForTesting
    val spamFilter: SpamFilter get() = spamFilterProvider.get()

    @get:VisibleForTesting
    val inProgress: Boolean get() = runner.isRunning

    /** The objects of the identity and session being wiped, read once so every stage acts on them. */
    private inner class Old {
        /** Set when an object could not be read. The stages that need it then fail, the rest run. */
        var readFailed = false
            private set

        private fun <T> read(provider: Provider<T>): Lazy<T> = lazy { provider.get() }.also {
            try {
                it.value
            } catch (e: Exception) {
                Log.e(TAG, "Could not read an object to wipe: ${e.javaClass.simpleName}")
                readFailed = true
            }
        }

        val state by read(stateProvider)
        private val sessionMesh by read(sessionMeshProvider)
        val mesh by lazy { sessionMesh.unified }
        val messageManager by read(messageManagerProvider)
        val channelManager by read(channelManagerProvider)
        val privateChatManager by read(privateChatManagerProvider)
        val dataManager by read(dataManagerProvider)
        val mediaSendingManager by read(mediaSendingManagerProvider)
        val notificationManager by read(notificationManagerProvider)
        val geohashSession by read(geohashSessionProvider)
        val secureIdentity by read(secureIdentityProvider)
        val routerHolder by read(messageRouterHolder)
        val conversationListPreferences by read(conversationListPreferencesProvider)
        val seenMessageStore by read(seenMessageStoreProvider)
        val locationChannelManager by read(locationChannelManagerProvider)
        val geohashBookmarksStore by read(geohashBookmarksStoreProvider)
        val appStateStore by read(appStateStoreProvider)
        val spamFilter by read(spamFilterProvider)
        val favorites by read(this@PanicClear.favorites)
    }

    /** Wipes everything and goes on under a new identity. Ignored while one runs, whoever started it. */
    fun run() {
        runner.runOnce {
            val old = Old()
            identityHolder.beginEnding()
            try {
                wipeThenRebuild(old)
            } finally {
                // Already off if the identity was replaced. Otherwise it stays, so things may start on it.
                identityHolder.cancelEnding()
            }
        }
    }

    private suspend fun wipeThenRebuild(old: Old) {
        Log.w(TAG, "Panic started")
        // Every stage that wipes data at rest reports here. The success message is a security
        // assurance, so it requires all of them, not just the conversation database.
        var wipeComplete = true

        /** One stage; if it throws the panic goes on, and cannot then claim to have completed. */
        fun stage(name: String, block: () -> Unit) {
            try {
                block()
            } catch (e: Exception) {
                Log.e(TAG, "Stage failed: $name: ${e.javaClass.simpleName}")
                wipeComplete = false
            }
        }

        /** A stage that confirms its wipe by returning true. */
        fun confirmedStage(name: String, block: () -> Boolean) = stage(name) {
            if (!block()) wipeComplete = false
        }

        // 1. Location off, and a pending one-shot media downgrade must not survive to become
        // actionable against the fresh identity.
        try {
            old.locationChannelManager.disableLocationServices()
        } catch (_: Exception) { }
        stage("media consent") { old.mediaSendingManager.clearPendingPrivateMediaConsent() }

        // 2. Stop admission before storage is wiped. Sends first: nothing still mining goes out during
        // the wipe. The store's gate also rejects any transport callback already in flight, and that
        // store's gate is never opened again.
        stage("geohash sends") { old.geohashSession.cancelSendsInFlight() }
        try {
            old.mesh.clearAllInternalData()
        } catch (e: Exception) {
            Log.e(TAG, "Could not clear the mesh's data: ${e.javaClass.simpleName}")
        }
        val databaseWiped = try {
            old.appStateStore.panicClearPrivateConversations()
        } catch (e: Exception) {
            Log.e(TAG, "The conversation wipe threw: ${e.javaClass.simpleName}")
            false
        }

        // An object that could not be read leaves the old identity in place, like a failed wipe.
        val conversationsCleared = databaseWiped && !old.readFailed

        // 3. Blank the screen at once. The stages that follow are slow, and someone under duress must
        // not see messages during them. These stay even with the rebuild for that reason alone.
        stage("store") { old.appStateStore.clear() }
        stage("messages") { old.messageManager.clearAllMessages() }
        stage("channels") { old.channelManager.clearAllChannels() }
        stage("private chats") { old.privateChatManager.clearAllPrivateChats() }
        try {
            blankFrame.await()
        } catch (e: Exception) {
            Log.e(TAG, "Could not blank the frame: ${e.javaClass.simpleName}")
            wipeComplete = false
        }

        // 4. Wipe data at rest. Which stages report a result stays as it was: the first two and the
        // notifications report nothing beyond not throwing.
        stage("data") { old.dataManager.clearAllData() }
        stage("list preferences") { old.conversationListPreferences.clearAll() }
        stage("seen messages") { old.seenMessageStore.clear() }
        stage("outbox") { old.routerHolder.peek()?.clearAll() }
        confirmedStage("keys") { clearAllCryptographicData(old) }
        stage("notifications") { old.notificationManager.clearAllNotifications(removeConversationShortcuts = true) }
        confirmedStage("media") { FileUtils.clearAllMedia(context) }
        stage("bookmarks") { old.geohashBookmarksStore.clearAll() }
        stage("selected channel") { old.locationChannelManager.clearPersistedChannel() }
        confirmedStage("geohash") { old.geohashSession.panicReset() }

        // 5. The new nickname, from storage for the session to come and in the state for this one.
        val newNickname = "anon${Random.nextInt(1000, 9999)}"
        stage("nickname") {
            old.dataManager.saveNickname(newNickname)
            old.state.setNickname(newNickname)
        }

        // 6. Privacy wins over availability: a new identity's store would open the gate over a
        // database that may still hold conversations, so this one stays, with its gate closed.
        if (!conversationsCleared) {
            Log.e(TAG, "Panic incomplete: the conversation database wipe failed")
            // The filter keeps recent message text and sender keys, and no rebuild drops them here.
            try {
                old.spamFilter.reset()
            } catch (_: Exception) { }
            // A wipe that does not commit must be as visible as the wipe itself: the person who
            // triggered it may be relying on the device being clean.
            try {
                old.messageManager.addSystemMessage(context.getString(R.string.system_panic_incomplete))
            } catch (e: Exception) {
                Log.e(TAG, "Could not show that the panic is incomplete: ${e.javaClass.simpleName}")
            }
            return
        }

        // 7.
        rebuild(old, newNickname, wipeComplete)
    }

    /**
     * Ends the old session and identity and starts their replacements. Not suspending, and on the main
     * thread: while this runs, nothing that lives on that thread (onResume, the Activity's collectors,
     * the foreground service) can build or start the new identity's mesh before its start-up has run.
     *
     * The session is built before the identity starts. Whatever the start-up's end wakes up, such as
     * the Activity attaching its delegate, finds the new session rather than building a stray one.
     */
    private fun rebuild(old: Old, newNickname: String, wipeComplete: Boolean) {
        var complete = wipeComplete
        try {
            old.mesh.delegate = null
            old.mesh.stopServices()
        } catch (e: Exception) {
            Log.e(TAG, "Could not stop the old mesh: ${e.javaClass.simpleName}")
            complete = false
        }
        sessionHolder.end()
        try {
            // The location wipe arrives with the new panic order; until then there is nothing to run here.
            val replaced = identityHolder.replace { true }
            if (replaced.failedCleanups.isNotEmpty()) {
                Log.e(TAG, "Cleanups failed: ${replaced.failedCleanups}")
                complete = false
            }
            if (!replaced.built) complete = false
        } catch (e: Exception) {
            // The next read tries the build again, and the steps below are that read.
            Log.e(TAG, "Building the next identity failed: ${e.javaClass.simpleName}")
            complete = false
        }

        var hasSession = false
        try {
            // False when the person left the app during the wipe: then nothing is left to show on.
            hasSession = sessionHolder.replace()
            identityStartup.onIdentityStarted()
            val mesh = meshProvider.get()
            if (hasSession) {
                // The mesh announces with the session's nickname, before the session has read it back.
                stateProvider.get().setNickname(newNickname)
                mesh.delegate = meshDelegateProvider.get()
            }
            mesh.startServices()
            mesh.sendBroadcastAnnounce()
            if (hasSession) sessionStartupProvider.get().start()
        } catch (e: Exception) {
            Log.e(TAG, "Starting the new identity failed: ${e.javaClass.simpleName}")
            complete = false
        }

        // In a duress scenario "did it work?" must not be a guess: the natural response to uncertainty
        // is to trigger the wipe again. Announce success only when every stage confirmed its wipe.
        val outcome = if (complete) {
            Log.w(TAG, "Panic completed")
            R.string.system_panic_completed
        } else {
            Log.e(TAG, "Panic incomplete: a stage did not confirm")
            R.string.system_panic_incomplete
        }
        if (hasSession) messageManagerProvider.get().addSystemMessage(context.getString(outcome))
    }

    /** Returns true only when every identity, key, and favorites store confirmed its wipe. */
    private fun clearAllCryptographicData(old: Old): Boolean {
        var cleared = true
        try {
            // Clear encryption service persistent identity (Ed25519 signing keys)
            if (!old.mesh.clearAllEncryptionData()) cleared = false

            // Clear secure identity state, and the secure values FavoritesPersistenceService uses
            // (favorites + peerID index)
            try {
                if (!old.secureIdentity.clearIdentityData()) cleared = false
                if (!old.secureIdentity.clearSecureValuesSynchronously("favorite_relationships", "favorite_peerid_index")) {
                    cleared = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not clear the secure identity state: ${e.javaClass.simpleName}")
                cleared = false
            }

            // Clear FavoritesPersistenceService persistent relationships
            try {
                old.favorites.clearAllFavorites()
            } catch (_: Exception) {
                cleared = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not clear the cryptographic data: ${e.javaClass.simpleName}")
            cleared = false
        }
        return cleared
    }
}
