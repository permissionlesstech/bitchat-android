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
    }
}
