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
        val ring = GeoRing(
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

    @Test
    fun compactRingBoundsCullOnlyDistantViews() {
        val ring = GeoRing(
            coords = floatArrayOf(
                -2f, -2f,
                -2f, 2f,
                2f, 2f,
                2f, -2f
            ),
            size = 4
        )

        assertTrue(
            ring.sphericalBounds.mayIntersectView(
                viewCenterX = 1f,
                viewCenterY = 0f,
                viewCenterZ = 0f,
                viewAngularRadius = 0.1f
            )
        )
        assertFalse(
            ring.sphericalBounds.mayIntersectView(
                viewCenterX = -1f,
                viewCenterY = 0f,
                viewCenterZ = 0f,
                viewAngularRadius = 0.1f
            )
        )
    }

    @Test
    fun limbPoint_intersectsTheTrueProjectionHorizon() {
        val point = limbPoint(
            DiscPt(x = 0.2f, y = 0f, front = false, depth = -0.8f),
            DiscPt(x = 0.6f, y = 0f, front = true, depth = 0.8f)
        )

        assertEquals(1f, point.first, 0.0001f)
        assertEquals(0f, point.second, 0.0001f)
    }

    @Test
    fun fillPolygon_replacesBacksideVerticesWithOneSmoothLimbArc() {
        val polygon = buildFillPolygon(
            pts = listOf(
                DiscPt(-0.6f, -0.2f, front = true, depth = 0.5f),
                DiscPt(-0.2f, 0.1f, front = false, depth = -0.5f),
                DiscPt(0.2f, -0.1f, front = false, depth = -0.5f),
                DiscPt(0.6f, -0.2f, front = true, depth = 0.5f),
                DiscPt(0f, -0.6f, front = true, depth = 0.5f)
            ),
            cx = 0f,
            cy = 0f,
            r = 1f
        )

        assertTrue(polygon.size >= 5)
        assertTrue(polygon.all { (x, y) -> x * x + y * y <= 1.0001f })
        assertFalse(polygon.any { (x, y) ->
            kotlin.math.abs(x + 0.2f) < 0.0001f &&
                kotlin.math.abs(y - 0.1f) < 0.0001f
        })
    }
}
