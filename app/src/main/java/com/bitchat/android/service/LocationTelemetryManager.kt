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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class LocationTelemetryManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "LocationTelemetryMgr"
        private const val INTERVAL_MS = 5_000L
    }

    private val locationProvider = SystemLocationProvider(context)
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                try {
                    val location = requestLocation()
                    if (location != null) {
                        // Store last known location for distance calculations
                        try { MeshServiceHolder.lastKnownLocation = location } catch (_: Exception) { }
                        AppStateStore.updateMyLocation(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            timestampMs = if (location.time > 0L) location.time else System.currentTimeMillis()
                        )
                        MeshServiceHolder.meshService?.sendLocationTelemetry(location)
                            ?: Log.w(TAG, "Mesh service unavailable; skipping location telemetry")
                    } else {
                        Log.w(TAG, "No location fix available yet")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Location telemetry loop failed: ${e.message}", e)
                }
                delay(INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        locationProvider.cancel()
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private suspend fun requestLocation(): Location? {
        if (!hasLocationPermission()) {
            Log.w(TAG, "Location permission missing; telemetry disabled")
            return null
        }

        return suspendCancellableCoroutine { cont ->
            locationProvider.requestFreshLocation { location ->
                cont.resume(location)
            }
        }
    }
}
