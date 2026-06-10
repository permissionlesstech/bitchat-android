package com.bitchat.android.geohash

import android.content.Context
import android.location.Geocoder

/**
 * Factory to provide the best available geocoder.
 */
object GeocoderFactory {
    fun get(context: Context): GeocoderProvider {
        // If Google Play Services Geocoder is present, use it.
        // Otherwise, fall back to OpenStreetMap.
        return if (Geocoder.isPresent()) {
            AndroidGeocoderProvider(context)
        } else {
            OpenStreetMapGeocoderProvider()
        }
    }
}
