package com.bitchat.android.geohash

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource

internal class FusedLocationProvider(private val context: Context) : LocationProvider {

    companion object {
        private const val TAG = "FusedLocationProvider"
    }

    private val fusedLocationClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)
    private val systemFallback = SystemLocationProvider(context)

    // Map to keep track of callbacks to remove them later
    private val activeCallbacks = mutableMapOf<(Location) -> Unit, LocationCallback>()
    private val activeCurrentLocationRequests = mutableSetOf<CancellationTokenSource>()

    private fun hasLocationPermission(): Boolean {
        return LiveLocationPrivacyGate.isEnabled &&
            (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            )
    }

    @SuppressLint("MissingPermission")
    override fun getLastKnownLocation(callback: (Location?) -> Unit) {
        if (!hasLocationPermission()) {
            callback(null)
            return
        }

        try {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location ->
                    if (location != null && LiveLocationPrivacyGate.isEnabled) {
                        callback(location)
                    } else {
                        systemFallback.getLastKnownLocation(callback)
                    }
                }
                .addOnFailureListener {
                    Log.e(TAG, "Error getting last-known fused location")
                    systemFallback.getLastKnownLocation(callback)
                }
        } catch (_: Exception) {
            Log.e(TAG, "Exception getting last-known fused location")
            systemFallback.getLastKnownLocation(callback)
        }
    }

    @SuppressLint("MissingPermission")
    override fun requestFreshLocation(callback: (Location?) -> Unit) {
        if (!hasLocationPermission()) {
            callback(null)
            return
        }

        try {
            val request = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setDurationMillis(30000)
                .build()
            val cancellation = CancellationTokenSource()

            synchronized(activeCurrentLocationRequests) {
                activeCurrentLocationRequests.add(cancellation)
            }

            fusedLocationClient.getCurrentLocation(request, cancellation.token)
                .addOnSuccessListener { location ->
                    if (location != null && LiveLocationPrivacyGate.isEnabled) {
                        callback(location)
                    } else {
                        systemFallback.requestFreshLocation(callback)
                    }
                }
                .addOnFailureListener {
                    Log.e(TAG, "Error getting fresh fused location")
                    systemFallback.requestFreshLocation(callback)
                }
                .addOnCompleteListener {
                    synchronized(activeCurrentLocationRequests) {
                        activeCurrentLocationRequests.remove(cancellation)
                    }
                }
        } catch (_: Exception) {
            Log.e(TAG, "Exception getting fresh fused location")
            systemFallback.requestFreshLocation(callback)
        }
    }

    @SuppressLint("MissingPermission")
    override fun requestLocationUpdates(
        intervalMs: Long,
        minDistanceMeters: Float,
        callback: (Location) -> Unit
    ) {
        if (!hasLocationPermission()) return

        try {
            val request = LocationRequest.Builder(intervalMs)
                .setMinUpdateDistanceMeters(minDistanceMeters)
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .build()

            val locationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    if (LiveLocationPrivacyGate.isEnabled) {
                        result.lastLocation?.let { callback(it) }
                    }
                }
            }

            synchronized(activeCallbacks) {
                activeCallbacks[callback] = locationCallback
            }

            fusedLocationClient.requestLocationUpdates(
                request,
                locationCallback,
                Looper.getMainLooper()
            ).addOnSuccessListener {
                Log.d(TAG, "Registered fused updates")
            }.addOnFailureListener {
                val shouldStartFallback = synchronized(activeCallbacks) {
                    activeCallbacks[callback] === locationCallback
                }
                if (shouldStartFallback) {
                    Log.w(TAG, "Fused updates unavailable; using system location provider")
                    systemFallback.requestLocationUpdates(intervalMs, minDistanceMeters, callback)
                }
            }

        } catch (_: Exception) {
            Log.e(TAG, "Error requesting fused updates")
            synchronized(activeCallbacks) {
                activeCallbacks.remove(callback)
            }
            systemFallback.requestLocationUpdates(intervalMs, minDistanceMeters, callback)
        }
    }

    override fun removeLocationUpdates(callback: (Location) -> Unit) {
        val locationCallback = synchronized(activeCallbacks) {
            activeCallbacks.remove(callback)
        }
        try {
            if (locationCallback != null) {
                fusedLocationClient.removeLocationUpdates(locationCallback)
                Log.d(TAG, "Removed fused updates")
            }
        } catch (_: Exception) {
            Log.e(TAG, "Error removing fused updates")
        }
        systemFallback.removeLocationUpdates(callback)
    }

    override fun cancel() {
        try {
            synchronized(activeCallbacks) {
                for ((_, locationCallback) in activeCallbacks) {
                    fusedLocationClient.removeLocationUpdates(locationCallback)
                }
                activeCallbacks.clear()
            }
            synchronized(activeCurrentLocationRequests) {
                activeCurrentLocationRequests.forEach { it.cancel() }
                activeCurrentLocationRequests.clear()
            }
            Log.d(TAG, "Cancelled all fused updates")
        } catch (_: Exception) {
            Log.e(TAG, "Error cancelling fused provider")
        }
        systemFallback.cancel()
    }
}
