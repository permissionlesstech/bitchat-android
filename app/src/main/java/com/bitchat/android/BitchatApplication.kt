package com.bitchat.android

import android.app.Application
import com.bitchat.android.di.FavoritesInitRetry
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.mesh.PeerFingerprintManager
import com.bitchat.android.mesh.PowerManager
import com.bitchat.android.nostr.GeohashAliasRegistry
import com.bitchat.android.nostr.GeohashConversationRegistry
import com.bitchat.android.nostr.LocationNotesInitializer
import com.bitchat.android.nostr.NostrIdentityBridge
import com.bitchat.android.nostr.NostrBackgroundRuntime
import com.bitchat.android.nostr.NostrTransport
import com.bitchat.android.nostr.PoWPreferenceManager
import com.bitchat.android.nostr.RelayDirectory
import com.bitchat.android.service.MeshServicePreferences
import com.bitchat.android.service.MeshTransportToggles
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.SeenMessageStore
import com.bitchat.android.spam.SpamFilter
import com.bitchat.android.spam.SpamFilterPreferences
import com.bitchat.android.ui.NostrBackgroundEventProcessor
import com.bitchat.android.ui.debug.DebugPreferenceManager
import com.bitchat.android.ui.debug.DebugSettingsManager
import com.bitchat.android.wifiaware.WifiAwareController
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

    // Lazy, so each is first touched where onCreate touched it before Hilt injected them. That
    // matters most for the stores that read persisted state: Hilt injects these fields inside
    // super.onCreate(), before the init calls below, and a bridge that reads a store before its
    // init (the favourites, for one) would throw.
    @Inject lateinit var torManager: Lazy<ArtiTorManager>
    @Inject lateinit var relayDirectory: Lazy<RelayDirectory>
    @Inject lateinit var locationNotesInitializer: Lazy<LocationNotesInitializer>
    @Inject lateinit var nostrBackgroundRuntime: Lazy<NostrBackgroundRuntime>
    @Inject lateinit var powPreferences: Lazy<PoWPreferenceManager>
    @Inject lateinit var nostrTransport: Lazy<NostrTransport>
    @Inject lateinit var powerManager: Lazy<PowerManager>
    @Inject lateinit var favoritesInit: FavoritesInitRetry
    @Inject lateinit var favoritesPersistence: Lazy<FavoritesPersistenceService>
    @Inject lateinit var appStateStore: Lazy<AppStateStore>
    @Inject lateinit var contactDirectory: Lazy<ContactDirectory>
    @Inject lateinit var nostrIdentityBridge: Lazy<NostrIdentityBridge>
    @Inject lateinit var spamFilterPreferences: Lazy<SpamFilterPreferences>
    @Inject lateinit var spamFilter: Lazy<SpamFilter>
    @Inject lateinit var debugPreferences: Lazy<DebugPreferenceManager>
    @Inject lateinit var wifiAwareController: Lazy<WifiAwareController>
    @Inject lateinit var debugSettings: Lazy<DebugSettingsManager>
    @Inject lateinit var meshTransportToggles: Lazy<MeshTransportToggles>
    @Inject lateinit var geohashAliases: Lazy<GeohashAliasRegistry>
    @Inject lateinit var geohashConversations: Lazy<GeohashConversationRegistry>
    @Inject lateinit var peerFingerprints: Lazy<PeerFingerprintManager>
    @Inject lateinit var seenMessageStore: Lazy<SeenMessageStore>
    @Inject lateinit var meshServicePreferences: Lazy<MeshServicePreferences>

    override fun onCreate() {
        super.onCreate()

        // Start the single process-wide power policy before transport components are constructed.
        powerManager.get().start()

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
            favoritesInit.initialize()
        } catch (_: Exception) { }

        // Restore private conversations before background transports can deliver new messages.
        // AppStateStore merges any in-flight arrivals by message ID, so startup cannot replace
        // newer transport state with an older database snapshot.
        try {
            appStateStore.get().initializeConversationPersistence(this)
        } catch (_: Exception) { }

        // Warm up Nostr identity to ensure npub is available for favorite notifications
        try {
            nostrIdentityBridge.get().getCurrentNostrIdentity(this)
        } catch (_: Exception) { }

        // Restore the spam filter toggle before transports start admitting public messages
        spamFilterPreferences.get().init(this)

        // Initialize theme preference
        ThemePreferenceManager.init(this)

        // Initialize chat UI mode (matrix transcript vs bubbles)
        com.bitchat.android.ui.theme.ChatUiModeManager.init(this)

        // Initialize debug preference manager (persists debug toggles)
        try { debugPreferences.get().init(this) } catch (_: Exception) { }

        // Initialize Wi‑Fi Aware controller with persisted default
        try {
            val enabled = debugPreferences.get().getWifiAwareEnabled(false)
            wifiAwareController.get().initialize(this, enabled)
        } catch (_: Exception) { }

        // Let the shared debug toggles reach this app's transports
        debugSettings.get().transportToggles = meshTransportToggles.get()

        // Initialize Geohash Registries for persistence
        try {
            geohashAliases.get().initialize(this)
            geohashConversations.get().initialize(this)
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

        // Initialize mesh service preferences
        try { meshServicePreferences.get().init(this) } catch (_: Exception) { }

        // Proactively start the foreground service to keep mesh alive
        try { com.bitchat.android.service.MeshForegroundService.start(this) } catch (_: Exception) { }

        // TorManager already initialized above
    }
}
