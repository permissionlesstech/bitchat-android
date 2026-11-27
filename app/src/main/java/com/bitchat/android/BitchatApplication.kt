package com.bitchat.android

import android.app.Application
import com.bitchat.android.di.AppIdentityStartup
import com.bitchat.android.mesh.PowerManager
import com.bitchat.android.net.ArtiTorManager
import com.bitchat.android.nostr.RelayDirectory
import com.bitchat.android.panic.PanicCleanup
import com.bitchat.android.panic.StartupWipe
import com.bitchat.android.service.MeshServicePreferences
import com.bitchat.android.spam.SpamFilterPreferences
import com.bitchat.android.ui.debug.DebugPreferenceManager
import com.bitchat.android.ui.theme.ThemePreferenceManager
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Main application class for bitchat Android
 */
@HiltAndroidApp
class BitchatApplication : Application() {

    /**
     * Set when this launch finished a panic's wipe. The system keeps an Activity's saved state
     * outside the sandbox, so state saved before the panic (the back stack, composer drafts)
     * would come back over the wiped identity; the first Activity takes this and starts clean.
     */
    private val discardSavedScreens = java.util.concurrent.atomic.AtomicBoolean(false)

    /** True once, for the first Activity after a launch that finished a panic's wipe. */
    fun takeDiscardSavedScreens(): Boolean = discardSavedScreens.getAndSet(false)

    // Lazy, so each is first touched where onCreate touches it: Hilt injects these fields inside
    // super.onCreate(), before the init calls below. That matters most for the stores that read
    // persisted state.
    @Inject lateinit var torManager: Lazy<ArtiTorManager>
    @Inject lateinit var relayDirectory: Lazy<RelayDirectory>
    @Inject lateinit var powerManager: Lazy<PowerManager>
    @Inject lateinit var identityStartup: Lazy<AppIdentityStartup>
    @Inject lateinit var spamFilterPreferences: Lazy<SpamFilterPreferences>
    @Inject lateinit var debugPreferences: Lazy<DebugPreferenceManager>
    @Inject lateinit var meshServicePreferences: Lazy<MeshServicePreferences>
    @Inject lateinit var panicCleanups: Lazy<Set<@JvmSuppressWildcards PanicCleanup>>

    override fun onCreate() {
        // A pending panic marker means a wipe did not finish. Finish it before Hilt, which injects
        // inside super.onCreate(), or anything else reads storage. If it cannot, the app goes on
        // and IdentityHolder refuses to build.
        val pendingWipe = StartupWipe.finishPendingWipe(this)
        discardSavedScreens.set(pendingWipe.foundPending)

        super.onCreate()

        // The panic may have died before it undid what it left outside the sandbox: notifications,
        // conversation shortcuts, the clipboard. Undo them now, before this launch posts anything.
        if (pendingWipe.foundPending) StartupWipe.replayCleanups(panicCleanups.get())

        // Start the single process-wide power policy before transport components are constructed.
        powerManager.get().start()

        // Initialize Tor first so any early network goes over Tor
        try {
            val torProvider = torManager.get()
            torProvider.init(this)
        } catch (_: Exception){}

        // Initialize relay directory (loads assets/nostr_relays.csv)
        relayDirectory.get().initialize(this)

        // The identity's stored state: location notes, favorites, private conversations, Nostr identity
        identityStartup.get().restoreIdentityState()

        // Restore the spam filter toggle before transports start admitting public messages
        spamFilterPreferences.get().init(this)

        // Initialize theme preference
        ThemePreferenceManager.init(this)

        // Initialize chat UI mode (matrix transcript vs bubbles)
        com.bitchat.android.ui.theme.ChatUiModeManager.init(this)

        // Initialize debug preference manager (persists debug toggles)
        try { debugPreferences.get().init(this) } catch (_: Exception) { }

        // The identity's background work: Wi-Fi Aware, transport toggles, geohash registries, Nostr
        identityStartup.get().startIdentityServices()

        // Initialize mesh service preferences
        try { meshServicePreferences.get().init(this) } catch (_: Exception) { }

        // Proactively start the foreground service to keep mesh alive
        try { com.bitchat.android.service.MeshForegroundService.start(this) } catch (_: Exception) { }

        // TorManager already initialized above
    }
}
