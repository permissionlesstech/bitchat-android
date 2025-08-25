package com.bitchat.android

import android.app.Application
import com.bitchat.android.nostr.RelayDirectory

/**
 * Main application class for bitchat Android
 */
class BitchatApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize relay directory (loads assets/nostr_relays.csv)
        RelayDirectory.initialize(this)

        // Initialize favorites persistence early so MessageRouter/NostrTransport can use it on startup
        try {
            com.bitchat.android.favorites.FavoritesPersistenceService.initialize(this)
        } catch (_: Exception) { }

        // Warm up Nostr identity to ensure npub is available for favorite notifications
        try {
            com.bitchat.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(this)
        } catch (_: Exception) { }
    }
}
