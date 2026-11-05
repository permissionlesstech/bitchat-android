package com.bitchat.android.geohash

import android.content.Context
import android.location.Geocoder
import com.bitchat.android.net.TorMode
import com.bitchat.android.net.TorPreferenceManager

/**
 * Factory to provide the best available geocoder.
 *
 * When Tor is on, the platform geocoder is skipped. Play Services performs
 * that lookup in its own process, so it would leave the device from the real
 * IP with the precise fix attached. OpenStreetMap goes through the app's
 * Tor-routed client instead.
 */
object GeocoderFactory {
    fun get(context: Context): GeocoderProvider {
        val torOn = TorPreferenceManager.get(context) == TorMode.ON
        return if (selectPlatformGeocoder(torOn = torOn, platformGeocoderPresent = Geocoder.isPresent())) {
            AndroidGeocoderProvider(context)
        } else {
            OpenStreetMapGeocoderProvider()
        }
    }
}

/** True when the platform geocoder is safe to call. Tor always uses OpenStreetMap. */
internal fun selectPlatformGeocoder(torOn: Boolean, platformGeocoderPresent: Boolean): Boolean {
    return !torOn && platformGeocoderPresent
}

/**
 * Place-name lookups use the center of the neighborhood cell, not the raw fix.
 * A name for that cell does not need metre-level input.
 */
internal fun geocodeQueryPoint(
    latitude: Double,
    longitude: Double,
    precision: Int = GeohashChannelLevel.NEIGHBORHOOD.precision
): Pair<Double, Double> {
    return Geohash.decodeToCenter(Geohash.encode(latitude, longitude, precision))
}

