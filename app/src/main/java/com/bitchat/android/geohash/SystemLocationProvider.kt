package com.bitchat.android.geohash

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.ActivityCompat

class SystemLocationProvider(private val context: Context) : LocationProvider {

    companion object {
        private const val TAG = "SystemLocationProvider"

        // Reject fixes worse than this (meters). GPS outdoors is typically 3-15m;
        // NETWORK_PROVIDER can be 50-2000m+, so this threshold effectively filters
        // out bad network fixes while still allowing degraded-but-usable GPS fixes.
        private const val MAX_ACCEPTABLE_ACCURACY_METERS = 50f

        // How long to wait for a GPS fix before allowing a fallback.
        private const val GPS_FALLBACK_WINDOW_MS = 15000L
    }

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    
    // Map to keep track of listeners to unregister them later
    private val activeListeners = mutableMapOf<(Location) -> Unit, LocationListener>()
    private val activeOneShotListeners = mutableMapOf<(Location?) -> Unit, LocationListener>()
    private val activeOneShotRunnables = mutableMapOf<(Location?) -> Unit, Runnable>()

    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun isAcceptable(location: Location): Boolean {
        // Some devices report accuracy == 0 for stale/garbage fixes; treat as unacceptable.
        return location.hasAccuracy() && location.accuracy in 0.01f..MAX_ACCEPTABLE_ACCURACY_METERS
    }

    @SuppressLint("MissingPermission")
    override fun getLastKnownLocation(callback: (Location?) -> Unit) {
        if (!hasLocationPermission()) {
            callback(null)
            return
        }

        try {
            var bestLocation: Location? = null
            val providers = locationManager.getProviders(true)
            for (provider in providers) {
                val location = locationManager.getLastKnownLocation(provider) ?: continue
                // Pick by ACCURACY, not recency. A stale-but-accurate GPS fix beats
                // a fresh-but-rough network fix for this app's purposes.
                if (bestLocation == null ||
                    (location.hasAccuracy() && (!bestLocation.hasAccuracy() || location.accuracy < bestLocation.accuracy))
                ) {
                    bestLocation = location
                }
            }
            callback(bestLocation)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting last known location: ${e.message}")
            callback(null)
        }
    }

    @SuppressLint("MissingPermission")
    override fun requestFreshLocation(callback: (Location?) -> Unit) {
        if (!hasLocationPermission()) {
            callback(null)
            return
        }

        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Log.w(TAG, "GPS provider not enabled")
            callback(null)
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                locationManager.getCurrentLocation(
                    LocationManager.GPS_PROVIDER,
                    null,
                    context.mainExecutor
                ) { location ->
                    if (location != null && isAcceptable(location)) {
                        callback(location)
                    } else {
                        Log.w(TAG, "Fresh GPS fix rejected or null (accuracy=${location?.accuracy})")
                        callback(null)
                    }
                }
            } else {
                val timeoutRunnable = Runnable {
                    Log.w(TAG, "Location request timed out")
                    synchronized(activeOneShotListeners) {
                        val listener = activeOneShotListeners.remove(callback)
                        activeOneShotRunnables.remove(callback)
                        if (listener != null) {
                            try {
                                locationManager.removeUpdates(listener)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error removing timed out listener: ${e.message}")
                            }
                        }
                    }
                    callback(null)
                }

                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        synchronized(activeOneShotListeners) {
                            activeOneShotListeners.remove(callback)
                            val runnable = activeOneShotRunnables.remove(callback)
                            if (runnable != null) {
                                handler.removeCallbacks(runnable)
                            }
                        }
                        try {
                            locationManager.removeUpdates(this)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error removing updates in callback: ${e.message}")
                        }
                        if (isAcceptable(location)) {
                            callback(location)
                        } else {
                            Log.w(TAG, "Fresh GPS fix rejected (accuracy=${location.accuracy})")
                            callback(null)
                        }
                    }
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }

                synchronized(activeOneShotListeners) {
                    activeOneShotListeners[callback] = listener
                    activeOneShotRunnables[callback] = timeoutRunnable
                }

                locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, null)
                handler.postDelayed(timeoutRunnable, GPS_FALLBACK_WINDOW_MS)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting fresh location: ${e.message}")
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
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (isAcceptable(location)) {
                        callback(location)
                    } else {
                        Log.d(TAG, "Dropped low-accuracy update (accuracy=${location.accuracy})")
                    }
                }
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }

            synchronized(activeListeners) {
                activeListeners[callback] = listener
            }

            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    intervalMs,
                    minDistanceMeters,
                    listener
                )
                Log.d(TAG, "Registered updates for GPS_PROVIDER")
            } else {
                Log.w(TAG, "GPS provider not enabled - no location updates will be delivered")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error requesting location updates: ${e.message}")
        }
    }

    override fun removeLocationUpdates(callback: (Location) -> Unit) {
        try {
            val listener = synchronized(activeListeners) {
                activeListeners.remove(callback)
            }

            if (listener != null) {
                locationManager.removeUpdates(listener)
                Log.d(TAG, "Removed location updates")
            } else {
                Log.w(TAG, "removeLocationUpdates: no matching listener found for callback")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing updates: ${e.message}")
        }
    }

    override fun cancel() {
        try {
            synchronized(activeListeners) {
                for ((_, listener) in activeListeners) {
                    try { locationManager.removeUpdates(listener) } catch (_: Exception) {}
                }
                activeListeners.clear()
            }

            synchronized(activeOneShotListeners) {
                for ((_, listener) in activeOneShotListeners) {
                    try { locationManager.removeUpdates(listener) } catch (_: Exception) {}
                }
                activeOneShotListeners.clear()

                for ((_, runnable) in activeOneShotRunnables) {
                    handler.removeCallbacks(runnable)
                }
                activeOneShotRunnables.clear()
            }
            Log.d(TAG, "Cancelled all system location requests")
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling system provider: ${e.message}")
        }
    }
}
