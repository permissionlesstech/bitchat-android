package com.bitchat.android.geohash

import android.location.Address

/**
 * Interface for reverse geocoding providers.
 */
interface GeocoderProvider {
    /**
     * Get a list of Address objects from latitude and longitude.
     */
    suspend fun getFromLocation(latitude: Double, longitude: Double, maxResults: Int): List<Address>
}
