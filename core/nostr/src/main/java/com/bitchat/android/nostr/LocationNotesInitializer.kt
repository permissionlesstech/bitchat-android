package com.bitchat.android.nostr

import android.content.Context
import android.util.Log

/**
 * Initializer for LocationNotesManager with all dependencies
 * Extracts initialization logic from MainActivity for better separation of concerns
 */
object LocationNotesInitializer {
    
    private const val TAG = "LocationNotesInitializer"
    
    /**
     * Initialize LocationNotesManager with all required dependencies
     * 
     * @param context Application context
     * @return true if initialization succeeded, false otherwise
     */
    fun initialize(context: Context): Boolean {
        return try {
            LocationNotesManager.getInstance().initialize(
                relayManager = { NostrRelayManager.getInstance(context) },
                subscribe = { filter, id, handler ->
                    // CRITICAL FIX: Extract geohash properly from filter using getGeohash() method
                    val geohashFromFilter = filter.getGeohash() ?: run {
                        Log.e(TAG, "❌ Cannot extract geohash from filter for location notes")
                        return@initialize id // Return subscription ID even on error
                    }
                    
                    val token = com.bitchat.android.geohash.LiveLocationPrivacyGate
                        .captureToken() ?: return@initialize id
                    NostrRelayManager.getInstance(context).subscribeForGeohash(
                        geohash = geohashFromFilter,
                        filter = filter,
                        id = id,
                        handler = handler,
                        includeDefaults = true,
                        nRelays = 5,
                        liveLocationToken = token
                    )
                },
                unsubscribe = { id ->
                    NostrRelayManager.getInstance(context).unsubscribe(id)
                },
                sendEvent = { event, relayUrls, token ->
                    if (relayUrls != null) {
                        NostrRelayManager.getInstance(context).sendEvent(
                            event,
                            relayUrls,
                            liveLocationToken = token
                        )
                    } else {
                        NostrRelayManager.getInstance(context).sendEvent(
                            event,
                            liveLocationToken = token
                        )
                    }
                },
                deriveIdentity = { geohash ->
                    NostrIdentityBridge.deriveIdentity(geohash, context)
                }
            )
            Log.d(TAG, "✅ Location Notes Manager initialized")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize Location Notes Manager: ${e.message}", e)
            false
        }
    }
}
