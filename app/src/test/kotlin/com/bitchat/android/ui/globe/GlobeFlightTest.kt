package com.bitchat.android.ui.globe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

class GlobeFlightTest {

    // A dive from the whole globe into a city-block cell about 57 degrees away.
    private val distance = 1.0
    private val globeWidth = 2.27
    private val blockWidth = 7e-5

    /** How far the target sits from screen center, in screen widths. */
    private fun targetOffset(fraction: Double, width: Double) = (1 - fraction) * distance / width

    @Test
    fun flight_startsAndEndsExactly() {
        val flight = GlobeFlight(distance, globeWidth, blockWidth)

        val (startFraction, startWidth) = flight.at(0.0)
        assertEquals(0.0, startFraction, 1e-9)
        assertEquals(globeWidth, startWidth, 1e-9)
        assertEquals(1.0 to blockWidth, flight.at(1.0))
    }

    @Test
    fun deepFlight_bringsTheTargetInSteadilyWithoutRacing() {
        val flight = GlobeFlight(distance, globeWidth, blockWidth)
        var previous = Double.MAX_VALUE
        var largestStep = 0.0
        val steps = 400
        for (i in 0..steps) {
            val (fraction, width) = flight.at(i.toDouble() / steps)
            val offset = targetOffset(fraction, width)
            assertTrue("target must never move away", offset <= previous + 1e-9)
            if (i > 0) largestStep = maxOf(largestStep, previous - offset)
            previous = offset
        }
        assertTrue("largest jump was $largestStep screens", largestStep < 0.05)
    }

    @Test
    fun linearPanWithExponentialZoom_racedAtTheEnd() {
        // The old path: just before landing the target was still many screens away.
        val t = 0.99
        val width = globeWidth * (blockWidth / globeWidth).pow(t)
        assertTrue(targetOffset(t, width) > 50.0)
    }

    @Test
    fun zoomOnly_isExponentialInWidth() {
        val flight = GlobeFlight(0.0, 1.0, 0.01)

        val (fraction, width) = flight.at(0.5)
        assertEquals(0.5, fraction, 1e-9)
        assertEquals(0.1, width, 1e-6)
    }

    @Test
    fun longerFlights_takeLonger() {
        assertTrue(
            GlobeFlight(distance, globeWidth, blockWidth).pathLength >
                GlobeFlight(distance, globeWidth, 0.1).pathLength
        )
    }

    @Test
    fun slerp_followsTheShortWayAcrossTheAntimeridian() {
        val (lat, lon) = GlobeFlight.slerp(0.0, 170.0, 0.0, -170.0, 0.5)

        assertEquals(0.0, lat, 1e-9)
        assertEquals(180.0, abs(lon), 1e-6)
    }

    @Test
    fun slerp_hitsBothEndpoints() {
        val (lat0, lon0) = GlobeFlight.slerp(10.0, 20.0, -30.0, 100.0, 0.0)
        val (lat1, lon1) = GlobeFlight.slerp(10.0, 20.0, -30.0, 100.0, 1.0)

        assertEquals(10.0, lat0, 1e-9)
        assertEquals(20.0, lon0, 1e-9)
        assertEquals(-30.0, lat1, 1e-9)
        assertEquals(100.0, lon1, 1e-9)
    }

    @Test
    fun arc_measuresGreatCircleAngles() {
        assertEquals(Math.PI / 2, GlobeFlight.arc(0.0, 0.0, 0.0, 90.0), 1e-9)
        assertEquals(Math.PI / 2, GlobeFlight.arc(0.0, 0.0, 90.0, 0.0), 1e-9)
        assertEquals(0.0, GlobeFlight.arc(45.0, 45.0, 45.0, 45.0), 1e-6)
    }
}
