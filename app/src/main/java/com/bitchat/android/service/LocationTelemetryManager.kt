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

        // High-accuracy live mode for smooth foreground Radar tracking
        private const val FOREGROUND_INTERVAL_MS = 1_500L
        private const val FOREGROUND_MIN_DISTANCE_METERS = 0.5f

        // Battery saver mode for background tracking
        private const val BACKGROUND_INTERVAL_MS = 30_000L
        private const val BACKGROUND_MIN_DISTANCE_METERS = 5.0f
    }

    private val locationProvider = SystemLocationProvider(context)
    private var isStarted = false
    private var isForeground = true

    private val locationCallback: (Location) -> Unit = { location ->
        try {
            Log.d(TAG, "📍 GPS update received (inForeground=$isForeground): lat=${location.latitude}, lon=${location.longitude}, acc=${location.accuracy}m")
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
        MeshServiceHolder.locationTelemetryManager = this

        // Prime once at startup with cached location if available
        try {
            locationProvider.getLastKnownLocation { cached ->
                if (cached != null) {
                    locationCallback(cached)
                }
            }
        } catch (_: Exception) {}

        // Initial registration based on current foreground state
        val initialInterval = if (isForeground) FOREGROUND_INTERVAL_MS else BACKGROUND_INTERVAL_MS
        val initialMinDistance = if (isForeground) FOREGROUND_MIN_DISTANCE_METERS else BACKGROUND_MIN_DISTANCE_METERS

        Log.i(TAG, "Starting GPS location tracking listener (interval=${initialInterval}ms, minDistance=${initialMinDistance}m)")
        locationProvider.requestLocationUpdates(
            intervalMs = initialInterval,
            minDistanceMeters = initialMinDistance,
            callback = locationCallback
        )
    }

    fun setAppForegroundState(inForeground: Boolean) {
        if (isForeground == inForeground) return
        isForeground = inForeground
        if (!isStarted) return

        if (inForeground) {
            Log.i(TAG, "⚡ Switching location provider to FOREGROUND high-accuracy mode (1.5s interval)")
            locationProvider.updateLocationRequestParams(
                callback = locationCallback,
                intervalMs = FOREGROUND_INTERVAL_MS,
                minDistanceMeters = FOREGROUND_MIN_DISTANCE_METERS
            )
        } else {
            Log.i(TAG, "🔋 Switching location provider to BACKGROUND battery-saver mode (30s interval + 5m filter)")
            locationProvider.updateLocationRequestParams(
                callback = locationCallback,
                intervalMs = BACKGROUND_INTERVAL_MS,
                minDistanceMeters = BACKGROUND_MIN_DISTANCE_METERS
            )
        }
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false
        if (MeshServiceHolder.locationTelemetryManager === this) {
            MeshServiceHolder.locationTelemetryManager = null
        }
        Log.i(TAG, "Stopping location tracking listener")
        locationProvider.removeLocationUpdates(locationCallback)
        locationProvider.cancel()
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}
