package com.bitchat.android.ui

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.bitchat.android.core.chat.R
import com.bitchat.android.di.CurrentSession
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.identity.scope.CurrentIdentity
import com.bitchat.android.identity.scope.IdentityHolder
import com.bitchat.android.identity.scope.IdentityStartup
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.panic.PanicCleanup
import com.bitchat.android.panic.PanicMarker
import com.bitchat.android.panic.ProcessRestarter
import com.bitchat.android.panic.SandboxWipeRunner
import com.bitchat.android.services.AppStateStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Inject
import javax.inject.Provider
import kotlin.random.Random

/**
 * The emergency wipe behind the triple tap. It marks the wipe as pending, stops admission, blanks
 * the screen, ends the session and the identity, wipes the sandbox by location, undoes what the
 * app left outside it, and then builds a new identity, so what was in memory or on disk is gone
 * without being listed here.
 *
 * The order of the steps is the contract: admission stops before storage is wiped, and a new
 * identity exists only once the wipe confirms. The app keeps running throughout: same Activity,
 * same process. Only a wipe or a teardown cleanup that cannot confirm restarts the process, with
 * the marker still set, so the next launch finishes the wipe. Screens follow the new session
 * through [ChatSessionHolder.generation].
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
    private val marker: PanicMarker,
    private val sandboxWipe: SandboxWipeRunner,
    private val cleanups: Set<@JvmSuppressWildcards PanicCleanup>,
    private val restarter: ProcessRestarter,
    @CurrentSession private val stateProvider: Provider<ChatState>,
    @CurrentSession private val sessionMeshProvider: Provider<ChatSessionMesh>,
    @CurrentSession private val meshDelegateProvider: Provider<ChatMeshDelegate>,
    @CurrentSession private val messageManagerProvider: Provider<MessageManager>,
    @CurrentSession private val channelManagerProvider: Provider<ChannelManager>,
    @CurrentSession private val privateChatManagerProvider: Provider<PrivateChatManager>,
    @CurrentSession private val dataManagerProvider: Provider<DataManager>,
    @CurrentSession private val mediaSendingManagerProvider: Provider<MediaSendingManager>,
    @CurrentSession private val geohashSessionProvider: Provider<GeohashSession>,
    @CurrentSession private val sessionStartupProvider: Provider<ChatSessionStartup>,
    @CurrentIdentity private val meshProvider: Provider<MeshService>,
    @CurrentIdentity private val locationChannelManagerProvider: Provider<LocationChannelManager>,
    @CurrentIdentity private val appStateStoreProvider: Provider<AppStateStore>,
) {

    private companion object {
        const val TAG = "PanicClear"
    }

    /** The current identity's store. */
    @get:VisibleForTesting
    val appStateStore: AppStateStore get() = appStateStoreProvider.get()

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

        private val sessionMesh by read(sessionMeshProvider)
        val mesh by lazy { sessionMesh.unified }
        val messageManager by read(messageManagerProvider)
        val channelManager by read(channelManagerProvider)
        val privateChatManager by read(privateChatManagerProvider)
        val mediaSendingManager by read(mediaSendingManagerProvider)
        val geohashSession by read(geohashSessionProvider)
        val locationChannelManager by read(locationChannelManagerProvider)
        val appStateStore by read(appStateStoreProvider)
    }

    /** Wipes everything and goes on under a new identity. Ignored while one runs, whoever started it. */
    fun run() {
        runner.runOnce {
            // 0. Before anything else, so a process death from here on finishes the wipe at the next launch.
            val markerSet = try {
                marker.set()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Could not write the panic marker: ${e.javaClass.simpleName}")
                false
            }
            val old = Old()
            identityHolder.beginEnding()
            try {
                wipeThenRebuild(old, markerSet)
            } finally {
                // Already off once the identity has ended.
                identityHolder.cancelEnding()
            }
        }
    }

    private suspend fun wipeThenRebuild(old: Old, markerSet: Boolean) {
        Log.w(TAG, "Panic started")
        // The success message is a security assurance, so every stage has to confirm, and a
        // marker that could not be written already rules it out.
        var complete = markerSet && !old.readFailed

        /** One stage; if it throws the panic goes on, and cannot then claim to have completed. */
        fun stage(name: String, block: () -> Unit) {
            try {
                block()
            } catch (e: Exception) {
                Log.e(TAG, "Stage failed: $name: ${e.javaClass.simpleName}")
                complete = false
            }
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
        stage("mesh data") { old.mesh.clearAllInternalData() }
        try {
            if (!old.appStateStore.panicClearPrivateConversations()) complete = false
        } catch (e: Exception) {
            Log.e(TAG, "The conversation wipe threw: ${e.javaClass.simpleName}")
            complete = false
        }

        // 3. Blank the screen at once. The steps that follow are slow, and someone under duress must
        // not see messages during them.
        stage("store") { old.appStateStore.clear() }
        stage("messages") { old.messageManager.clearAllMessages() }
        stage("channels") { old.channelManager.clearAllChannels() }
        stage("private chats") { old.privateChatManager.clearAllPrivateChats() }
        try {
            blankFrame.await()
        } catch (e: Exception) {
            Log.e(TAG, "Could not blank the frame: ${e.javaClass.simpleName}")
            complete = false
        }
        // What lives in the process rather than in the identity, which the wipe by location cannot
        // reach: the subscriptions on the process-wide relay manager. The identity storage managers
        // built so far are fenced in step 5, inside the storage lock.
        stage("geohash relays") { old.geohashSession.panicReleaseRelays() }

        // 4. The old mesh stops and reports to nobody before its identity ends.
        stage("old mesh") {
            old.mesh.delegate = null
            old.mesh.stopServices()
        }
        sessionHolder.end()

        // 5. End the identity and wipe the sandbox while none exists. Work of the ended identity and
        // session can still be finishing on other threads; it is kept out of the identity's
        // encrypted storage until the next identity is built over the wiped one.
        var built = false
        var wipeConfirmed = false
        try {
            val replaced = SecureIdentityStateManager.excludingOtherThreads {
                identityHolder.replace {
                    wipeSandbox().also { wipeConfirmed = it }
                }
            }
            if (replaced.failedCleanups.isNotEmpty()) {
                Log.e(TAG, "Teardown cleanups failed: ${replaced.failedCleanups}")
            }
            built = replaced.built
        } catch (e: Exception) {
            Log.e(TAG, "Replacing the identity failed: ${e.javaClass.simpleName}")
        }
        // Set again for any restart, even after a confirmed wipe: a teardown or build that failed
        // means the old identity's work may still be writing, so the next launch wipes once more.
        // Also when the identity's end threw before the wipe could run.
        if (!built || !wipeConfirmed) holdMarker()
        if (!built) complete = false

        // 6. What the app left outside its sandbox. A failure here is reported, never restarted for:
        // a new process would not undo it either.
        for (cleanup in cleanups) {
            val undone = try {
                cleanup.run()
            } catch (e: Exception) {
                Log.e(TAG, "System-side cleanup threw: ${cleanup.name}: ${e.javaClass.simpleName}")
                false
            }
            if (!undone) {
                Log.e(TAG, "System-side cleanup failed: ${cleanup.name}")
                complete = false
            }
        }

        // 7. No identity in this process after a wipe or a teardown that did not confirm.
        if (!built) {
            Log.e(TAG, "Panic incomplete: restarting to finish the wipe")
            restarter.restart()
            return
        }

        // 8.
        startNext(complete)
    }

    /**
     * Runs the sandbox wipe inside [IdentityHolder.replace], and clears the marker only once it
     * confirms. It must not read the identity: the holder's lock is reentrant, so a read here would
     * build an identity in the middle of the wipe.
     */
    private fun wipeSandbox(): Boolean {
        val result = try {
            sandboxWipe.run()
        } catch (e: Exception) {
            Log.e(TAG, "The sandbox wipe threw: ${e.javaClass.simpleName}")
            null
        }
        val confirmed = result?.confirmed == true
        if (confirmed) {
            Log.w(TAG, "Sandbox wiped")
            marker.clear()
        } else {
            // Counts only: the paths themselves are what the wipe was meant to remove.
            if (result != null) Log.e(TAG, "Sandbox wipe left ${result.leftovers.size} paths and ${result.aliasesLeft} keys")
            holdMarker()
        }
        return confirmed
    }

    /**
     * Makes sure the marker is set after a wipe that did not confirm, writing it again if the first
     * write failed: while it is set, nothing builds an identity in this process and the next launch
     * wipes again.
     */
    private fun holdMarker() {
        try {
            if (!marker.pending) marker.set()
        } catch (e: Exception) {
            Log.e(TAG, "Could not write the panic marker: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Starts the new identity and, if one is wanted, its session. Not suspending, and on the main
     * thread: while this runs, nothing that lives on that thread (onResume, the Activity's
     * collectors, the foreground service) can build or start the new identity's mesh before its
     * start-up has run.
     *
     * The session is built before the identity starts. Whatever the start-up's end wakes up, such as
     * the Activity attaching its delegate, finds the new session rather than building a stray one.
     */
    private fun startNext(wipeComplete: Boolean) {
        var complete = wipeComplete
        var hasSession = false
        try {
            // False when the person left the app during the wipe: then nothing is left to show on,
            // and the next session's own nickname load makes a fresh one.
            hasSession = sessionHolder.replace()
            identityStartup.onIdentityStarted()
            val mesh = meshProvider.get()
            if (hasSession) {
                // The mesh announces with the session's nickname, before the session has read it back.
                try {
                    val newNickname = "anon${Random.nextInt(1000, 9999)}"
                    dataManagerProvider.get().saveNickname(newNickname)
                    stateProvider.get().setNickname(newNickname)
                } catch (e: Exception) {
                    Log.e(TAG, "Could not set the new nickname: ${e.javaClass.simpleName}")
                    complete = false
                }
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
}
