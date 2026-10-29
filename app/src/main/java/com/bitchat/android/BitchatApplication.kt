package com.bitchat.android

import android.app.Application
import com.bitchat.android.nostr.LocationNotesInitializer
import com.bitchat.android.nostr.NostrBackgroundRuntime
import com.bitchat.android.nostr.NostrTransport
import com.bitchat.android.nostr.PoWPreferenceManager
import com.bitchat.android.nostr.RelayDirectory
import com.bitchat.android.ui.NostrBackgroundEventProcessor
import com.bitchat.android.ui.theme.ThemePreferenceManager
import com.bitchat.android.net.ArtiTorManager
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Main application class for bitchat Android
 */
@HiltAndroidApp
class BitchatApplication : Application() {

    // Lazy, so each is first touched where onCreate touched it before Hilt injected them.
    @Inject lateinit var torManager: Lazy<ArtiTorManager>
    @Inject lateinit var relayDirectory: Lazy<RelayDirectory>
    @Inject lateinit var locationNotesInitializer: Lazy<LocationNotesInitializer>
    @Inject lateinit var nostrBackgroundRuntime: Lazy<NostrBackgroundRuntime>
    @Inject lateinit var powPreferences: Lazy<PoWPreferenceManager>
    @Inject lateinit var nostrTransport: Lazy<NostrTransport>

    override fun onCreate() {
        super.onCreate()

        // Start the single process-wide power policy before transport components are constructed.
        com.bitchat.android.mesh.PowerManager.getInstance(this).start()

        // Initialize Tor first so any early network goes over Tor
        try {
            val torProvider = torManager.get()
            torProvider.init(this)
        } catch (_: Exception){}

        // Initialize relay directory (loads assets/nostr_relays.csv)
        relayDirectory.get().initialize(this)

        // Initialize LocationNotesManager dependencies early so sheet subscriptions can start immediately
        try { locationNotesInitializer.get().initialize(this) } catch (_: Exception) { }

        // Initialize favorites persistence early so MessageRouter/NostrTransport can use it on startup
        try {
            com.bitchat.android.favorites.FavoritesPersistenceService.initialize(this)
        } catch (_: Exception) { }

        // Restore private conversations before background transports can deliver new messages.
        // AppStateStore merges any in-flight arrivals by message ID, so startup cannot replace
        // newer transport state with an older database snapshot.
        try {
            com.bitchat.android.services.AppStateStore.initializeConversationPersistence(this)
        } catch (_: Exception) { }

        // Warm up Nostr identity to ensure npub is available for favorite notifications
        try {
            com.bitchat.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(this)
        } catch (_: Exception) { }

        // Restore the spam filter toggle before transports start admitting public messages
        com.bitchat.android.spam.SpamFilterPreferences.init(this)

        // Initialize theme preference
        ThemePreferenceManager.init(this)

        // Initialize chat UI mode (matrix transcript vs bubbles)
        com.bitchat.android.ui.theme.ChatUiModeManager.init(this)

        // Initialize debug preference manager (persists debug toggles)
        try { com.bitchat.android.ui.debug.DebugPreferenceManager.init(this) } catch (_: Exception) { }

        // Initialize Wi‑Fi Aware controller with persisted default
        try {
            val enabled = com.bitchat.android.ui.debug.DebugPreferenceManager.getWifiAwareEnabled(false)
            com.bitchat.android.wifiaware.WifiAwareController.initialize(this, enabled)
        } catch (_: Exception) { }

        // Let the shared debug toggles reach this app's transports
        com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().transportToggles =
            com.bitchat.android.service.MeshTransportToggles

        // Initialize Geohash Registries for persistence
        try {
            com.bitchat.android.nostr.GeohashAliasRegistry.initialize(this)
            com.bitchat.android.nostr.GeohashConversationRegistry.initialize(this)
        } catch (_: Exception) { }

        // Own relay connectivity, selected-channel subscriptions, and presence scheduling at the
        // process level so closing the Activity does not disconnect Nostr.
        try {
            nostrBackgroundRuntime.get().initialize(this) { app, scope ->
                NostrBackgroundEventProcessor(
                    app,
                    scope,
                    nostrBackgroundRuntime.get().participants,
                    powPreferences.get(),
                    nostrTransport = { nostrTransport.get() },
                    appStateStore = com.bitchat.android.services.AppStateStore,
                    contactDirectory = com.bitchat.android.services.ContactDirectory,
                    favorites = Lazy { com.bitchat.android.favorites.FavoritesPersistenceService.shared },
                    peerFingerprints = com.bitchat.android.mesh.PeerFingerprintManager.getInstance(),
                    geohashAliases = com.bitchat.android.nostr.GeohashAliasRegistry,
                    geohashConversations = com.bitchat.android.nostr.GeohashConversationRegistry,
                    nostrIdentity = com.bitchat.android.nostr.NostrIdentityBridge,
                    spamFilter = com.bitchat.android.spam.SpamFilter.shared,
                    seenMessageStore = Lazy {
                        com.bitchat.android.services.SeenMessageStore.getInstance(app)
                    },
                )
            }
        } catch (_: Exception) { }

        // Initialize mesh service preferences
        try { com.bitchat.android.service.MeshServicePreferences.init(this) } catch (_: Exception) { }

        // Proactively start the foreground service to keep mesh alive
        try { com.bitchat.android.service.MeshForegroundService.start(this) } catch (_: Exception) { }

        // TorManager already initialized above
    }
}
