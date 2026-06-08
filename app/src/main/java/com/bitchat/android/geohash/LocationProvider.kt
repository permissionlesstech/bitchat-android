package com.bitchat.android.geohash

import android.location.Location

/**
 * Abstraction for location providers to support both
 * System (LocationManager) and Google Play Services (FusedLocationProvider).
 */
interface LocationProvider {
    /**
     * Get the last known location from cache.
     * @param callback Called with the location or null if not found/error.
     */
    fun getLastKnownLocation(callback: (Location?) -> Unit)

    /**
     * Request a single, fresh location update.
     * @param callback Called with the location or null if failed.
     */
    fun requestFreshLocation(callback: (Location?) -> Unit)

    /**
     * Request continuous location updates.
     * @param intervalMs Desired interval in milliseconds.
     * @param minDistanceMeters Minimum distance in meters.
     * @param callback Called when location updates.
     */
    fun requestLocationUpdates(intervalMs: Long, minDistanceMeters: Float, callback: (Location) -> Unit)

    /**
     * Stop location updates.
     * @param callback The same callback instance passed to requestLocationUpdates.
     */
    fun removeLocationUpdates(callback: (Location) -> Unit)

    /**
     * Cancel any pending one-shot location requests and cleanup resources.
     */
    fun cancel()
}
