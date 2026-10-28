package com.bitchat.android.geohash

import android.content.Context
import android.location.Geocoder
import com.bitchat.android.nostr.NostrHttpClients
import javax.inject.Provider

/**
 * Factory to provide the best available geocoder.
 */
object GeocoderFactory {
    fun get(
        context: Context,
        liveLocationGate: LiveLocationPrivacyGate,
        clients: Provider<NostrHttpClients>
    ): GeocoderProvider {
        // If Google Play Services Geocoder is present, use it.
        // Otherwise, fall back to OpenStreetMap.
        return if (Geocoder.isPresent()) {
            AndroidGeocoderProvider(context, liveLocationGate)
        } else {
            OpenStreetMapGeocoderProvider(clients, liveLocationGate)
        }
    }
}
