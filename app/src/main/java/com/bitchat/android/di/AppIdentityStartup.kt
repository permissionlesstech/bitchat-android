package com.bitchat.android.di

import android.app.Application
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.identity.scope.CurrentIdentity
import com.bitchat.android.identity.scope.IdentityHolder
import com.bitchat.android.identity.scope.IdentityStartup
import com.bitchat.android.mesh.PeerFingerprintManager
import com.bitchat.android.nostr.GeohashAliasRegistry
import com.bitchat.android.nostr.GeohashConversationRegistry
import com.bitchat.android.nostr.LocationNotesInitializer
import com.bitchat.android.nostr.NostrBackgroundRuntime
import com.bitchat.android.nostr.NostrIdentityBridge
import com.bitchat.android.nostr.NostrTransport
import com.bitchat.android.nostr.PoWPreferenceManager
import com.bitchat.android.service.MeshTransportToggles
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.SeenMessageStore
import com.bitchat.android.spam.SpamFilter
import com.bitchat.android.ui.NostrBackgroundEventProcessor
import com.bitchat.android.ui.debug.DebugPreferenceManager
import com.bitchat.android.ui.debug.DebugSettingsManager
import com.bitchat.android.wifiaware.WifiAwareController
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The phone's start-up of one identity. Every object is asked for when its step runs, so a run
 * after a replacement reaches the new identity's objects, and building this class touches none.
 *
 * It comes in two halves because `Application.onCreate` loads the process-wide preferences between
 * them, and a cold start keeps that order: [startIdentityServices] reads the debug preferences.
 * Those preferences load once per process, so a later identity runs both halves straight through.
 */
@Singleton
class AppIdentityStartup @Inject constructor(
    private val application: Application,
    private val identity: IdentityHolder,
    private val debugPreferences: Lazy<DebugPreferenceManager>,
    private val debugSettings: Lazy<DebugSettingsManager>,
    private val powPreferences: Lazy<PoWPreferenceManager>,
    @CurrentIdentity private val locationNotesInitializer: Provider<LocationNotesInitializer>,
    @CurrentIdentity private val favoritesPersistence: Provider<FavoritesPersistenceService>,
    @CurrentIdentity private val appStateStore: Provider<AppStateStore>,
    @CurrentIdentity private val nostrIdentityBridge: Provider<NostrIdentityBridge>,
    @CurrentIdentity private val wifiAwareController: Provider<WifiAwareController>,
    @CurrentIdentity private val meshTransportToggles: Provider<MeshTransportToggles>,
    @CurrentIdentity private val geohashAliases: Provider<GeohashAliasRegistry>,
    @CurrentIdentity private val geohashConversations: Provider<GeohashConversationRegistry>,
    @CurrentIdentity private val nostrBackgroundRuntime: Provider<NostrBackgroundRuntime>,
    @CurrentIdentity private val nostrTransport: Provider<NostrTransport>,
    @CurrentIdentity private val contactDirectory: Provider<ContactDirectory>,
    @CurrentIdentity private val peerFingerprints: Provider<PeerFingerprintManager>,
    @CurrentIdentity private val spamFilter: Provider<SpamFilter>,
    @CurrentIdentity private val seenMessageStore: Provider<SeenMessageStore>,
) : IdentityStartup {

    private val _startedGeneration = MutableStateFlow(NOT_STARTED)

    /**
     * The identity generation whose start-up last finished. What follows the identity from outside
     * it watches this rather than [IdentityHolder.generation], which moves before the new identity
     * has restored anything.
     */
    val startedGeneration: StateFlow<Int> = _startedGeneration.asStateFlow()

    override fun onIdentityStarted() {
        restoreIdentityState()
        startIdentityServices()
    }

    /** The first half: what the identity has stored. */
    fun restoreIdentityState() {
        // Initialize LocationNotesManager dependencies early so sheet subscriptions can start immediately
        try { locationNotesInitializer.get().initialize(application) } catch (_: Exception) { }

        // Initialize favorites persistence early so MessageRouter/NostrTransport can use it on startup
        try {
            favoritesPersistence.get().initialize()
        } catch (_: Exception) { }

        // Restore private conversations before background transports can deliver new messages.
        // AppStateStore merges any in-flight arrivals by message ID, so startup cannot replace
        // newer transport state with an older database snapshot.
        try {
            appStateStore.get().initializeConversationPersistence(application)
        } catch (_: Exception) { }

        // Warm up Nostr identity to ensure npub is available for favorite notifications
        try {
            nostrIdentityBridge.get().getCurrentNostrIdentity(application)
        } catch (_: Exception) { }
    }

    /** The second half: what runs in the background for it. Needs the debug preferences loaded. */
    fun startIdentityServices() {
        // Initialize Wi‑Fi Aware controller with persisted default
        try {
            val enabled = debugPreferences.get().getWifiAwareEnabled(false)
            wifiAwareController.get().initialize(application, enabled)
        } catch (_: Exception) { }

        // Let the shared debug toggles reach this identity's transports
        debugSettings.get().transportToggles = meshTransportToggles.get()

        // Initialize Geohash Registries for persistence
        try {
            geohashAliases.get().initialize(application)
            geohashConversations.get().initialize(application)
        } catch (_: Exception) { }

        // Own relay connectivity, selected-channel subscriptions, and presence scheduling at the
        // identity level so closing the Activity does not disconnect Nostr.
        try {
            nostrBackgroundRuntime.get().initialize(application) { app, scope ->
                NostrBackgroundEventProcessor(
                    app,
                    scope,
                    nostrBackgroundRuntime.get().participants,
                    powPreferences.get(),
                    nostrTransport = { nostrTransport.get() },
                    appStateStore = appStateStore.get(),
                    contactDirectory = contactDirectory.get(),
                    favorites = favoritesPersistence,
                    peerFingerprints = peerFingerprints.get(),
                    geohashAliases = geohashAliases.get(),
                    geohashConversations = geohashConversations.get(),
                    nostrIdentity = nostrIdentityBridge.get(),
                    spamFilter = spamFilter.get(),
                    seenMessageStore = seenMessageStore,
                )
            }
        } catch (_: Exception) { }

        _startedGeneration.value = identity.generation.value
    }

    companion object {
        /** [startedGeneration] before the first identity has started. */
        const val NOT_STARTED = -1
    }
}
