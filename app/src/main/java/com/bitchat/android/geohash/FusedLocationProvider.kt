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

class FusedLocationProvider(private val context: Context) : LocationProvider {

    companion object {
        private const val TAG = "FusedLocationProvider"
    }

    private val fusedLocationClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)
    
    // Map to keep track of callbacks to remove them later
    private val activeCallbacks = mutableMapOf<(Location) -> Unit, LocationCallback>()

    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
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
                    callback(location)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Error getting last known fused location: ${e.message}")
                    callback(null)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Exception getting last known fused location: ${e.message}")
            callback(null)
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

            fusedLocationClient.getCurrentLocation(request, null)
                .addOnSuccessListener { location ->
                    callback(location)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Error getting fresh fused location: ${e.message}")
                    callback(null)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Exception getting fresh fused location: ${e.message}")
            callback(null)
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
                    result.lastLocation?.let { callback(it) }
                }
            }

            synchronized(activeCallbacks) {
                activeCallbacks[callback] = locationCallback
            }

            fusedLocationClient.requestLocationUpdates(
                request,
                locationCallback,
                Looper.getMainLooper()
            )
            Log.d(TAG, "Registered fused updates")

        } catch (e: Exception) {
            Log.e(TAG, "Error requesting fused updates: ${e.message}")
        }
    }

    override fun removeLocationUpdates(callback: (Location) -> Unit) {
        try {
            val locationCallback = synchronized(activeCallbacks) {
                activeCallbacks.remove(callback)
            }

            if (locationCallback != null) {
                fusedLocationClient.removeLocationUpdates(locationCallback)
                Log.d(TAG, "Removed fused updates")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing fused updates: ${e.message}")
        }
    }

    override fun cancel() {
        try {
            synchronized(activeCallbacks) {
                for ((callback, locationCallback) in activeCallbacks) {
                    fusedLocationClient.removeLocationUpdates(locationCallback)
                }
                activeCallbacks.clear()
            }
            Log.d(TAG, "Cancelled all fused updates")
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling fused provider: ${e.message}")
        }
    }
}
