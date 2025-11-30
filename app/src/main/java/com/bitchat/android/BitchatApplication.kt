package com.bitchat.android

import android.app.Application
import com.bitchat.android.di.initKoin
import com.bitchat.android.ui.theme.ThemePreferenceManager
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger

/**
 * Main application class for bitchat Android
 */
class BitchatApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()

        initKoin{
            androidContext(this@BitchatApplication)
            androidLogger()
        }

        // Warm up Nostr identity to ensure npub is available for favorite notifications
        try {
            com.bitchat.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(this)
        } catch (_: Exception) { }

        // Initialize theme preference
        ThemePreferenceManager.init(this)
    }
}
