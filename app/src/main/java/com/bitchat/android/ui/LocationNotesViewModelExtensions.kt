package com.bitchat.android.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.geohash.GeohashChannelLevel
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.nostr.LocationNotesManager
import kotlinx.coroutines.launch

/**
 * Extension functions for ChatViewModel to manage LocationNotesManager subscription
 * Extracts location notes subscription logic for better separation of concerns
 */

private const val TAG = "LocationNotesVM"

/**
 * Initialize location notes manager subscription (no separate counter)
 * Subscribes/unsubscribes based on channel selection and location permission
 */
fun ChatViewModel.initializeLocationNotesManagerSubscription() {
    viewModelScope.launch {
        // Observe channel changes
        selectedLocationChannel.observeForever { channel ->
            updateLocationNotesSubscription(channel)
        }
    }
}

/**
 * Update location notes subscription based on current channel and location permission
 * iOS pattern: Subscribe when in mesh mode and location is authorized
 */
private fun ChatViewModel.updateLocationNotesSubscription(channel: ChannelID?) {
    try {
        val locationManager = LocationChannelManager.getInstance(getApplication<Application>())
        val permissionState = locationManager.permissionState.value
        val locationPermissionGranted = permissionState == LocationChannelManager.PermissionState.AUTHORIZED
        
        when (channel) {
            is ChannelID.Mesh -> {
                // Mesh mode: subscribe to building-level geohash notes if location authorized
                if (locationPermissionGranted) {
                    // Refresh location to get current building geohash
                    locationManager.refreshChannels()
                    
                    // Get building-level geohash (precision 8, same as iOS)
                    val buildingGeohash = locationManager.availableChannels.value
                        ?.firstOrNull { it.level == GeohashChannelLevel.BUILDING }
                        ?.geohash
                    
                    if (buildingGeohash != null) {
                        Log.d(TAG, "📍 Subscribing to location notes for building geohash: $buildingGeohash")
                        LocationNotesManager.getInstance().setGeohash(buildingGeohash)
                    } else {
                        // Cancel if no building geohash available
                        if (LocationNotesManager.getInstance().geohash.value == null) {
                            LocationNotesManager.getInstance().cancel()
                        }
                    }
                } else {
                    LocationNotesManager.getInstance().cancel()
                }
            }
            is ChannelID.Location -> {
                // Location channel mode: cancel (only show in mesh mode)
                LocationNotesManager.getInstance().cancel()
            }
            null -> {
                // Default to mesh behavior
                if (locationPermissionGranted) {
                    locationManager.refreshChannels()
                }
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "updateLocationNotesSubscription failed: ${e.message}")
    }
}
