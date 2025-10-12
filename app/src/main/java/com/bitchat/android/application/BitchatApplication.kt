package com.bitchat.android.application

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.nostr.RelayDirectory
import com.bitchat.android.net.TorManager
import com.bitchat.android.nostr.NostrIdentityBridge
import com.bitchat.android.ui.debug.DebugPreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Main application class for bitchat Android
 */
class BitchatApplication : Application() {

    lateinit var container: AppContainer
    private val applicationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    override fun onCreate() {
        super.onCreate()

        // Initialize Tor first so any early network goes over Tor
        try {
            TorManager.init(this)
        } catch (_: Exception) {
        }

        // Initialize relay directory (loads assets/nostr_relays.csv)
        RelayDirectory.initialize(this)

        // Initialize favorites persistence early so MessageRouter/NostrTransport can use it on startup
        try {
            FavoritesPersistenceService.initialize(this)
        } catch (_: Exception) {
        }

        // Warm up Nostr identity to ensure npub is available for favorite notifications
        try {
            NostrIdentityBridge.getCurrentNostrIdentity(this)
        } catch (_: Exception) {
        }

        // Initialize debug preference manager (persists debug toggles)
        try {
            DebugPreferenceManager.init(this)
        } catch (_: Exception) {
        }

        // TorManager already initialized above

        container = AppDataContainer(
            applicationScope, this.applicationContext,
            BluetoothMeshService(this), this
        )
    }
}

fun CreationExtras.bitchatApplication(): BitchatApplication =
    (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as BitchatApplication)
