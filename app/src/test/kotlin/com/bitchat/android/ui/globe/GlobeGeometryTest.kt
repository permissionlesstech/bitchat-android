package com.bitchat.android.ui.globe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobeGeometryTest {

    private val square = listOf(
        DiscPt(-0.5f, -0.5f, front = true),
        DiscPt(0.5f, -0.5f, front = true),
        DiscPt(0.5f, 0.5f, front = true),
        DiscPt(-0.5f, 0.5f, front = true)
    )

    @Test
    fun closedFullyVisibleRing_connectsLastPointToFirst() {
        val run = buildFrontRuns(square, closed = true).single()

        assertEquals(square.size + 1, run.size)
        assertEquals(run.first(), run.last())
    }

    @Test
    fun openFullyVisibleLine_doesNotConnectLastPointToFirst() {
        val run = buildFrontRuns(square, closed = false).single()

        assertEquals(square.size, run.size)
        assertNotEquals(run.first(), run.last())
    }

    @Test
    fun polygonContains_distinguishesInsideAndOutsidePoints() {
        val polygon = listOf(
            -10f to -10f,
            10f to -10f,
            10f to 10f,
            -10f to 10f
        )

        assertTrue(polygonContains(polygon, 0f, 0f))
        assertFalse(polygonContains(polygon, 20f, 0f))
    }

    @Test
    fun ringContainsLocation_distinguishesInsideAndOutsideCoordinates() {
        val ring = LandData.Ring(
            coords = floatArrayOf(
                -10f, -10f,
                -10f, 10f,
                10f, 10f,
                10f, -10f
            ),
            size = 4
        )

        assertTrue(ringContainsLocation(ring, latitude = 0.0, longitude = 0.0))
        assertFalse(ringContainsLocation(ring, latitude = 20.0, longitude = 0.0))
    }
}
