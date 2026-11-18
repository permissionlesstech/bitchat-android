package com.bitchat.android.geohash

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class DistanceLocationServiceTest {
    
    @Test
    fun `calculateDistance returns zero for same location`() {
        val location = DistanceLocationService.GeoLocation(
            latitude = 40.7128,
            longitude = -74.0060,
            peerId = "peer1"
        )
        
        val distance = DistanceLocationService.calculateDistance(location, location)
        
        assertEquals(0.0, distance, 0.1)
    }
    
    @Test
    fun `calculateDistance between New York and Los Angeles`() {
        // NYC: 40.7128° N, 74.0060° W
        // LA: 34.0522° N, 118.2437° W
        // Approximate distance: 3944 km
        
        val distance = DistanceLocationService.calculateDistance(
            lat1 = 40.7128,
            lon1 = -74.0060,
            lat2 = 34.0522,
            lon2 = -118.2437
        )
        
        // Expected: ~3944 km, with some tolerance for approximation
        val expected = 3944000.0 // meters
        assertTrue(abs(distance - expected) < 50000) // within 50 km
    }
    
    @Test
    fun `calculateDistance 100 meters apart`() {
        // Two points roughly 100 meters apart (approximation for testing)
        val distance = DistanceLocationService.calculateDistance(
            lat1 = 0.0,
            lon1 = 0.0,
            lat2 = 0.0009,
            lon2 = 0.0 // roughly 100 meters at equator
        )
        
        assertTrue(distance > 90 && distance < 110)
    }
    
    @Test
    fun `calculateBearing north direction`() {
        val bearing = DistanceLocationService.calculateBearing(
            lat1 = 0.0,
            lon1 = 0.0,
            lat2 = 1.0,
            lon2 = 0.0 // North
        )
        
        assertTrue(bearing < 10 || bearing > 350)
    }
    
    @Test
    fun `calculateBearing east direction`() {
        val bearing = DistanceLocationService.calculateBearing(
            lat1 = 0.0,
            lon1 = 0.0,
            lat2 = 0.0,
            lon2 = 1.0 // East
        )
        
        assertTrue(bearing > 80 && bearing < 100)
    }
    
    @Test
    fun `findNearbyPeers filters by radius`() {
        val currentLocation = DistanceLocationService.GeoLocation(
            latitude = 40.7128,
            longitude = -74.0060,
            peerId = "me"
        )
        
        val peers = listOf(
            DistanceLocationService.GeoLocation(
                latitude = 40.7200,
                longitude = -74.0050,
                peerId = "peer1",
                nickname = "Alice"
            ),
            DistanceLocationService.GeoLocation(
                latitude = 40.7400,
                longitude = -74.0000,
                peerId = "peer2",
                nickname = "Bob"
            ),
            DistanceLocationService.GeoLocation(
                latitude = 34.0522,
                longitude = -118.2437,
                peerId = "peer3",
                nickname = "Charlie"
            )
        )
        
        val nearby = DistanceLocationService.findNearbyPeers(
            currentLocation = currentLocation,
            peers = peers,
            radiusMeters = 5000.0 // 5 km radius
        )
        
        // peer1 and peer2 should be in the 5km radius, peer3 (LA) should not
        assertEquals(2, nearby.size)
        assertEquals("peer1", nearby[0].peerId) // Should be closest
        assertEquals("peer2", nearby[1].peerId)
    }
    
    @Test
    fun `findNearbyPeers returns empty for no peers in radius`() {
        val currentLocation = DistanceLocationService.GeoLocation(
            latitude = 40.7128,
            longitude = -74.0060,
            peerId = "me"
        )
        
        val peers = listOf(
            DistanceLocationService.GeoLocation(
                latitude = 34.0522,
                longitude = -118.2437,
                peerId = "peer1"
            )
        )
        
        val nearby = DistanceLocationService.findNearbyPeers(
            currentLocation = currentLocation,
            peers = peers,
            radiusMeters = 1000.0 // 1 km radius
        )
        
        assertTrue(nearby.isEmpty())
    }
    
    @Test
    fun `formatDistance handles meters`() {
        assertEquals("500 m", DistanceLocationService.formatDistance(500.0))
        assertEquals("999 m", DistanceLocationService.formatDistance(999.0))
    }
    
    @Test
    fun `formatDistance handles kilometers`() {
        assertEquals("1.5 km", DistanceLocationService.formatDistance(1500.0))
        assertEquals("50.0 km", DistanceLocationService.formatDistance(50000.0))
    }
    
    @Test
    fun `formatBearing north`() {
        assertEquals("N", DistanceLocationService.formatBearing(0f))
        assertEquals("N", DistanceLocationService.formatBearing(350f))
    }
    
    @Test
    fun `formatBearing cardinal directions`() {
        assertEquals("E", DistanceLocationService.formatBearing(90f))
        assertEquals("S", DistanceLocationService.formatBearing(180f))
        assertEquals("W", DistanceLocationService.formatBearing(270f))
    }
    
    @Test
    fun `formatBearing intercardinal directions`() {
        assertEquals("NE", DistanceLocationService.formatBearing(45f))
        assertEquals("SE", DistanceLocationService.formatBearing(135f))
        assertEquals("SW", DistanceLocationService.formatBearing(225f))
        assertEquals("NW", DistanceLocationService.formatBearing(315f))
    }
    
    @Test
    fun `isWithinRadius detects point inside circle`() {
        val isInside = DistanceLocationService.isWithinRadius(
            centerLat = 0.0,
            centerLon = 0.0,
            radiusMeters = 1000.0,
            testLat = 0.005,
            testLon = 0.005
        )
        
        assertTrue(isInside)
    }
    
    @Test
    fun `isWithinRadius detects point outside circle`() {
        val isInside = DistanceLocationService.isWithinRadius(
            centerLat = 0.0,
            centerLon = 0.0,
            radiusMeters = 1000.0,
            testLat = 1.0,
            testLon = 1.0
        )
        
        assertFalse(isInside)
    }
    
    @Test
    fun `calculateMidpoint between two points`() {
        // Midpoint between equator and 2 degrees north
        val (midLat, midLon) = DistanceLocationService.calculateMidpoint(
            lat1 = 0.0,
            lon1 = 0.0,
            lat2 = 2.0,
            lon2 = 0.0
        )
        
        // Should be approximately 1 degree north
        assertTrue(midLat > 0.9 && midLat < 1.1)
        assertTrue(midLon > -0.1 && midLon < 0.1)
    }
}
