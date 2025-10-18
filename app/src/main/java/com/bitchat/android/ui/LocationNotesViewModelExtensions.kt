package com.bitchat.android.ui

import android.app.Application
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.geohash.GeohashChannelLevel
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.nostr.LocationNotesManager
import com.bitchat.android.geohash.LocationChannelManager.PermissionState

private val BUILDING_LEVEL = GeohashChannelLevel.BUILDING

/**
 * Automatically keep LocationNotesManager in sync with mesh chat and location availability.
 * Subscribes to building-level notes when the user is in mesh chat and location access is ready.
 */
fun ChatViewModel.initializeLocationNotesManagerSubscription() {
    val app = getApplication<Application>()
    val locationManager = LocationChannelManager.getInstance(app)
    val notesManager = LocationNotesManager.getInstance()

    fun refreshNotesSubscription() {
        val permissionGranted = locationManager.permissionState.value == PermissionState.AUTHORIZED
        val servicesEnabled = locationManager.isLocationServicesEnabled()
        val viewingMesh = selectedLocationChannel.value is ChannelID.Mesh

        if (permissionGranted && servicesEnabled && viewingMesh) {
            val buildingChannel = locationManager.availableChannels.value
                ?.firstOrNull { it.level == BUILDING_LEVEL }

            if (buildingChannel != null) {
                notesManager.setGeohash(buildingChannel.geohash)
            } else {
                // Trigger a refresh; when channels arrive we'll get called again
                locationManager.refreshChannels()
            }
        } else {
            notesManager.cancel()
        }
    }

    selectedLocationChannel.observeForever { refreshNotesSubscription() }
    locationManager.permissionState.observeForever {
        if (it == PermissionState.AUTHORIZED) {
            locationManager.refreshChannels()
        }
        refreshNotesSubscription()
    }
    locationManager.availableChannels.observeForever { refreshNotesSubscription() }
    locationManager.locationServicesEnabled.observeForever { enabled ->
        if (enabled) {
            locationManager.refreshChannels()
        }
        refreshNotesSubscription()
    }

    // Evaluate immediately with current state
    refreshNotesSubscription()
}
