package com.bitchat.android.ui.globe

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobeMathTest {

    @Test
    fun preparedProjector_matchesReferenceProjection() {
        val centers = listOf(
            0.0 to 0.0,
            45.25 to 12.5,
            -72.0 to 179.5
        )
        val points = listOf(
            0.0 to 0.0,
            51.5074 to -0.1278,
            -33.8688 to 151.2093,
            89.0 to -179.9
        )

        for ((centerLat, centerLon) in centers) {
            val projector = GlobeMath.PreparedProjector(centerLat, centerLon)
            for ((lat, lon) in points) {
                val latRadians = Math.toRadians(lat)
                val lonRadians = Math.toRadians(lon)
                val terms = floatArrayOf(
                    sin(latRadians).toFloat(),
                    cos(latRadians).toFloat(),
                    sin(lonRadians).toFloat(),
                    cos(lonRadians).toFloat()
                )
                val actual = FloatArray(3)
                projector.project(terms, 0, actual, 0)
                val expected = GlobeMath.projectRaw(lat, lon, centerLat, centerLon)

                assertEquals(expected.x, actual[0], 0.000_002f)
                assertEquals(expected.y, actual[1], 0.000_002f)
                assertEquals(expected.cosC, actual[2], 0.000_002f)
            }
        }
    }

    @Test
    fun zoomForPrecision_staysWithinInteractiveBounds() {
        for (precision in 1..GlobeMath.MAX_PRECISION) {
            val zoom = GlobeMath.zoomForPrecision(
                precision = precision,
                baseRadiusPx = 400f,
                screenMinPx = 900f
            )

            assertTrue(zoom >= GlobeMath.MIN_ZOOM)
            assertTrue(zoom <= GlobeMath.MAX_ZOOM)
        }
    }
}
