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
    // Map to keep track of listeners to unregister them later
    private val activeListeners = mutableMapOf<(Location) -> Unit, LocationListener>()

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
                        // For older versions, use requestSingleUpdate
                        val listener = object : LocationListener {
                            override fun onLocationChanged(location: Location) {
                                callback(location)
                                locationManager.removeUpdates(this)
                            }
                            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                            override fun onProviderEnabled(provider: String) {}
                            override fun onProviderDisabled(provider: String) {}
                        }
                        locationManager.requestSingleUpdate(provider, listener, null)
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
}
