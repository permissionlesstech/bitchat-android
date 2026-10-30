package com.bitchat.android.nostr

import android.content.Context
import android.util.Log
import com.bitchat.android.geohash.LiveLocationPrivacyGate
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Initializer for LocationNotesManager with all dependencies
 * Extracts initialization logic from MainActivity for better separation of concerns
 */
@Singleton
class LocationNotesInitializer @Inject constructor(
    // Lazy: each is first touched where initialize asked for it before.
    private val notesManager: Lazy<LocationNotesManager>,
    private val relayManager: Lazy<NostrRelayManager>,
    private val liveLocationGate: LiveLocationPrivacyGate,
    private val nostrIdentity: NostrIdentityBridge,
) {

    private companion object {
        const val TAG = "LocationNotesInitializer"
    }

    /**
     * Initialize LocationNotesManager with all required dependencies
     * 
     * @param context Application context
     * @return true if initialization succeeded, false otherwise
     */
    fun initialize(context: Context): Boolean {
        return try {
            notesManager.get().initialize(
                relayManager = { relayManager.get() },
                subscribe = { filter, id, handler ->
                    // CRITICAL FIX: Extract geohash properly from filter using getGeohash() method
                    val geohashFromFilter = filter.getGeohash() ?: run {
                        Log.e(TAG, "❌ Cannot extract geohash from filter for location notes")
                        return@initialize id // Return subscription ID even on error
                    }
                    
                    val token = liveLocationGate.captureToken() ?: return@initialize id
                    relayManager.get().subscribeForGeohash(
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
                    relayManager.get().unsubscribe(id)
                },
                sendEvent = { event, relayUrls, token ->
                    if (relayUrls != null) {
                        relayManager.get().sendEvent(
                            event,
                            relayUrls,
                            liveLocationToken = token
                        )
                    } else {
                        relayManager.get().sendEvent(
                            event,
                            liveLocationToken = token
                        )
                    }
                },
                deriveIdentity = { geohash ->
                    nostrIdentity.deriveIdentity(geohash, context)
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
