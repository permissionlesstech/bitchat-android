package com.bitchat.android

import android.app.Application
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.identity.scope.CurrentIdentity
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
import javax.inject.Provider

/**
 * Main application class for bitchat Android
 */
@HiltAndroidApp
class BitchatApplication : Application() {

    // Lazy or Provider, so each is first touched where onCreate touched it before Hilt injected them. That
    // matters most for the stores that read persisted state: Hilt injects these fields inside
    // super.onCreate(), before the init calls below.
    @Inject lateinit var torManager: Lazy<ArtiTorManager>
    @Inject lateinit var relayDirectory: Lazy<RelayDirectory>
    @Inject @CurrentIdentity lateinit var locationNotesInitializer: Provider<LocationNotesInitializer>
    @Inject @CurrentIdentity lateinit var nostrBackgroundRuntime: Provider<NostrBackgroundRuntime>
    @Inject lateinit var powPreferences: Lazy<PoWPreferenceManager>
    @Inject @CurrentIdentity lateinit var nostrTransport: Provider<NostrTransport>
    @Inject lateinit var powerManager: Lazy<PowerManager>
    @Inject @CurrentIdentity lateinit var favoritesPersistence: Provider<FavoritesPersistenceService>
    @Inject @CurrentIdentity lateinit var appStateStore: Provider<AppStateStore>
    @Inject @CurrentIdentity lateinit var contactDirectory: Provider<ContactDirectory>
    @Inject @CurrentIdentity lateinit var nostrIdentityBridge: Provider<NostrIdentityBridge>
    @Inject lateinit var spamFilterPreferences: Lazy<SpamFilterPreferences>
    @Inject @CurrentIdentity lateinit var spamFilter: Provider<SpamFilter>
    @Inject lateinit var debugPreferences: Lazy<DebugPreferenceManager>
    @Inject @CurrentIdentity lateinit var wifiAwareController: Provider<WifiAwareController>
    @Inject lateinit var debugSettings: Lazy<DebugSettingsManager>
    @Inject @CurrentIdentity lateinit var meshTransportToggles: Provider<MeshTransportToggles>
    @Inject @CurrentIdentity lateinit var geohashAliases: Provider<GeohashAliasRegistry>
    @Inject @CurrentIdentity lateinit var geohashConversations: Provider<GeohashConversationRegistry>
    @Inject @CurrentIdentity lateinit var peerFingerprints: Provider<PeerFingerprintManager>
    @Inject @CurrentIdentity lateinit var seenMessageStore: Provider<SeenMessageStore>
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
            favoritesPersistence.get().initialize()
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
