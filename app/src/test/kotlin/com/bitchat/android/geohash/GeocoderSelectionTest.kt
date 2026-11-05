package com.bitchat.android.geohash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeocoderSelectionTest {
    @Test
    fun torUsesOpenStreetMapEvenWhenThePlatformGeocoderExists() {
        assertFalse(selectPlatformGeocoder(torOn = true, platformGeocoderPresent = true))
        assertFalse(selectPlatformGeocoder(torOn = true, platformGeocoderPresent = false))
    }

    @Test
    fun platformGeocoderIsUsedOnlyWhenTorIsOff() {
        assertTrue(selectPlatformGeocoder(torOn = false, platformGeocoderPresent = true))
        assertFalse(selectPlatformGeocoder(torOn = false, platformGeocoderPresent = false))
    }

    @Test
    fun reverseGeocodeAsksAboutTheNeighborhoodCellCenter() {
        val latitude = 37.7749
        val longitude = -122.4194
        val precision = GeohashChannelLevel.NEIGHBORHOOD.precision
        val query = geocodeQueryPoint(latitude, longitude)

        assertEquals(Geohash.encode(latitude, longitude, precision), Geohash.encode(query.first, query.second, precision))
        assertEquals(Geohash.decodeToCenter(Geohash.encode(latitude, longitude, precision)), query)
        assertTrue(query.first != latitude || query.second != longitude)
    }
}
