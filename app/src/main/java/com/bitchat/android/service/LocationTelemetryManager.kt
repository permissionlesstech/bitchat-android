package com.bitchat.android.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.core.content.ContextCompat
import com.bitchat.android.geohash.SystemLocationProvider
import com.bitchat.android.services.AppStateStore
import kotlinx.coroutines.CoroutineScope

class LocationTelemetryManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "LocationTelemetryMgr"
        private const val UPDATE_INTERVAL_MS = 2_000L
        private const val MIN_DISTANCE_METERS = 0f
    }

    private val locationProvider = SystemLocationProvider(context)
    private var isStarted = false

    private val locationCallback: (Location) -> Unit = { location ->
        try {
            Log.d(TAG, "📍 Live GPS update received: lat=${location.latitude}, lon=${location.longitude}, acc=${location.accuracy}m")
            try { MeshServiceHolder.lastKnownLocation = location } catch (_: Exception) { }
            AppStateStore.updateMyLocation(
                latitude = location.latitude,
                longitude = location.longitude,
                timestampMs = if (location.time > 0L) location.time else System.currentTimeMillis()
            )
            MeshServiceHolder.meshService?.sendLocationTelemetry(location)
                ?: Log.w(TAG, "Mesh service unavailable; skipping location telemetry")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process location update: ${e.message}", e)
        }
    }

    fun start() {
        if (isStarted) return
        if (!hasLocationPermission()) {
            Log.w(TAG, "Location permission missing; telemetry disabled")
            return
        }
        isStarted = true

        // Prime once at startup with cached location if available
        try {
            locationProvider.getLastKnownLocation { cached ->
                if (cached != null) {
                    locationCallback(cached)
                }
            }
        } catch (_: Exception) {}

        // Register for continuous hardware GPS updates (every 2s / 0m movement)
        Log.i(TAG, "Starting continuous GPS location tracking listener")
        locationProvider.requestLocationUpdates(
            intervalMs = UPDATE_INTERVAL_MS,
            minDistanceMeters = MIN_DISTANCE_METERS,
            callback = locationCallback
        )
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false
        Log.i(TAG, "Stopping location tracking listener")
        locationProvider.removeLocationUpdates(locationCallback)
        locationProvider.cancel()
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}
