package com.bitchat.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

class RadarMathEngineTest {

    @Test
    fun testDistanceCalculation() {
        // Distance between two points (e.g. London to Paris)
        // London: 51.5074, -0.1278
        // Paris: 48.8566, 2.3522
        // Expected distance: ~344 km = 344000 meters
        val dist = RadarMathEngine.calculateDistance(51.5074, -0.1278, 48.8566, 2.3522)
        assertEquals(344000.0, dist, 2000.0) // Allow 2km margin for earth ellipsoidal variance vs spherical model
    }

    @Test
    fun testBearingCalculation() {
        // North
        val bearingNorth = RadarMathEngine.calculateBearing(0.0, 0.0, 1.0, 0.0)
        assertEquals(0.0, bearingNorth, 0.01)

        // East
        val bearingEast = RadarMathEngine.calculateBearing(0.0, 0.0, 0.0, 1.0)
        assertEquals(90.0, bearingEast, 0.01)

        // South
        val bearingSouth = RadarMathEngine.calculateBearing(0.0, 0.0, -1.0, 0.0)
        assertEquals(180.0, bearingSouth, 0.01)

        // West
        val bearingWest = RadarMathEngine.calculateBearing(0.0, 0.0, 0.0, -1.0)
        assertEquals(270.0, bearingWest, 0.01)
    }

    @Test
    fun testRelativeAngleCalculation() {
        // True North bearing = 90 (East), device heading = 45 (NE)
        // Relative angle should be 45 (NE relative to user heading forward)
        val rel1 = RadarMathEngine.calculateRelativeAngle(90.0, 45.0)
        assertEquals(45.0, rel1, 0.01)

        // True North bearing = 0 (North), device heading = 90 (East)
        // Relative angle should be 270 (North is to user's left/West when heading East)
        val rel2 = RadarMathEngine.calculateRelativeAngle(0.0, 90.0)
        assertEquals(270.0, rel2, 0.01)
    }

    @Test
    fun testCartesianMapping() {
        // At distance 50m with max range 100m, on a 200px max radius viewport.
        // Bearing 90 (East / Right side)
        val posEast = RadarMathEngine.toCartesian(50.0, 90.0, 100.0, 200.0)
        assertEquals(100.0, posEast.first, 0.01)
        assertEquals(0.0, posEast.second, 0.01)

        // Bearing 0 (North / Top side)
        val posNorth = RadarMathEngine.toCartesian(50.0, 0.0, 100.0, 200.0)
        assertEquals(0.0, posNorth.first, 0.01)
        assertEquals(-100.0, posNorth.second, 0.01)
    }
}
