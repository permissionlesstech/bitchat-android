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
                val location = locationManager.getLastKnownLocation(provider)
                if (location != null) {
                    if (bestLocation == null || location.time > bestLocation.time) {
                        bestLocation = location
                    }
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

        try {
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )

            var providerFound = false
            for (provider in providers) {
                if (locationManager.isProviderEnabled(provider)) {
                    Log.d(TAG, "Requesting fresh location from $provider")
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        locationManager.getCurrentLocation(
                            provider,
                            null,
                            context.mainExecutor
                        ) { location ->
                            callback(location)
                        }
                    } else {
                        // For older versions, use requestSingleUpdate with timeout mechanism
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
                                callback(location)
                            }
                            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                            override fun onProviderEnabled(provider: String) {}
                            override fun onProviderDisabled(provider: String) {}
                        }

                        synchronized(activeOneShotListeners) {
                            activeOneShotListeners[callback] = listener
                            activeOneShotRunnables[callback] = timeoutRunnable
                        }
                        
                        locationManager.requestSingleUpdate(provider, listener, null)
                        handler.postDelayed(timeoutRunnable, 30000L) // 30s timeout
                    }
                    providerFound = true
                    break
                }
            }

            if (!providerFound) {
                Log.w(TAG, "No location providers available for fresh location")
                callback(null)
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
                    callback(location)
                }
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }

            // Store the listener so we can remove it later
            synchronized(activeListeners) {
                activeListeners[callback] = listener
            }

            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            var registered = false
            
            for (provider in providers) {
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.requestLocationUpdates(
                        provider,
                        intervalMs,
                        minDistanceMeters,
                        listener
                    )
                    registered = true
                    Log.d(TAG, "Registered updates for $provider")
                }
            }
            
            if (!registered) {
                Log.w(TAG, "No providers enabled for continuous updates")
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
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing updates: ${e.message}")
        }
    }

    override fun cancel() {
        try {
            // Cancel continuous updates
            synchronized(activeListeners) {
                for ((_, listener) in activeListeners) {
                    try { locationManager.removeUpdates(listener) } catch (_: Exception) {}
                }
                activeListeners.clear()
            }

            // Cancel one-shot requests
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
