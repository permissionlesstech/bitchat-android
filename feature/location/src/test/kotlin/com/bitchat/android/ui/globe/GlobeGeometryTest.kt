package com.bitchat.android.ui.globe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class GlobeGeometryTest {

    /** Records emitted segments, splitting them into polylines wherever the pen lifts. */
    private class RecordingSink : SegmentSink {
        val polylines = mutableListOf<MutableList<Pair<Float, Float>>>()
        private var open = false

        override fun segment(x0: Float, y0: Float, x1: Float, y1: Float) {
            val current = polylines.lastOrNull()
            if (!open || current == null || current.last() != (x0 to y0)) {
                polylines += mutableListOf(x0 to y0)
            }
            polylines.last() += x1 to y1
            open = true
        }

        override fun lift() {
            open = false
        }
    }

    private val wideClip = ClipRect(-1000f, -1000f, 1000f, 1000f)

    /** Projected (x, y, cosC) triples for points on the near hemisphere. */
    private fun front(vararg xy: Float): FloatArray {
        val out = FloatArray(xy.size / 2 * 3)
        for (i in 0 until xy.size / 2) {
            val x = xy[i * 2]
            val y = xy[i * 2 + 1]
            out[i * 3] = x
            out[i * 3 + 1] = y
            out[i * 3 + 2] = sqrt(1f - x * x - y * y)
        }
        return out
    }

    private val square = front(-0.5f, -0.5f, 0.5f, -0.5f, 0.5f, 0.5f, -0.5f, 0.5f)

    @Test
    fun closedFullyVisibleRing_isOnePolylineEndingWhereItStarts() {
        val sink = RecordingSink()
        sink.addProjectedLine(square, 4, closed = true, cx = 0f, cy = 0f, r = 1f, clip = wideClip)

        val line = sink.polylines.single()
        assertEquals(5, line.size)
        assertEquals(line.first(), line.last())
    }

    @Test
    fun openFullyVisibleLine_doesNotConnectLastPointToFirst() {
        val sink = RecordingSink()
        sink.addProjectedLine(square, 4, closed = false, cx = 0f, cy = 0f, r = 1f, clip = wideClip)

        val line = sink.polylines.single()
        assertEquals(4, line.size)
        assertEquals(-0.5f to -0.5f, line.first())
        assertEquals(-0.5f to 0.5f, line.last())
    }

    @Test
    fun lineTurningOverTheHorizon_isCutAtTheLimb() {
        // From the view center out to a point a little way round the far side.
        val points = floatArrayOf(
            0f, 0f, 1f,
            0.9f, 0f, -0.2f
        )
        val sink = RecordingSink()
        sink.addProjectedLine(points, 2, closed = false, cx = 0f, cy = 0f, r = 1f, clip = wideClip)

        val end = sink.polylines.single().last()
        assertEquals(1f, end.first, 1e-4f)
        assertEquals(0f, end.second, 1e-4f)
    }

    @Test
    fun lineEntirelyOnTheFarSide_emitsNothing() {
        val points = floatArrayOf(
            0.2f, 0f, -0.9f,
            0.4f, 0f, -0.8f
        )
        val sink = RecordingSink()
        sink.addProjectedLine(points, 2, closed = false, cx = 0f, cy = 0f, r = 1f, clip = wideClip)

        assertTrue(sink.polylines.isEmpty())
    }

    @Test
    fun segmentLeavingTheClip_isCutAtTheClipEdge() {
        val sink = RecordingSink()
        sink.addProjectedLine(
            front(0f, 0f, 0.8f, 0f), 2, closed = false,
            cx = 0f, cy = 0f, r = 1000f,
            clip = ClipRect(-500f, -500f, 500f, 500f)
        )

        assertEquals(listOf(0f to 0f, 500f to 0f), sink.polylines.single())
    }

    @Test
    fun polygonInsideTheClip_isReturnedUnchanged() {
        val polygon = PointBuffer().apply {
            add(-10f, -10f); add(10f, -10f); add(10f, 10f); add(-10f, 10f)
        }
        val result = clipPolygon(polygon, ClipRect(-100f, -100f, 100f, 100f), PointBuffer(), PointBuffer())

        assertSame(polygon, result)
    }

    @Test
    fun polygonCrossingTheClip_isTrimmedToIt() {
        val polygon = PointBuffer().apply {
            add(-10f, -10f); add(30f, -10f); add(30f, 10f); add(-10f, 10f)
        }
        val result = clipPolygon(polygon, ClipRect(-100f, -100f, 20f, 100f), PointBuffer(), PointBuffer())

        assertEquals(4, result.size)
        for (i in 0 until result.size) assertTrue(result.x(i) <= 20f)
        assertEquals(30f * 20f, kotlin.math.abs(signedArea(result)), 1e-3f)
    }

    @Test
    fun signedArea_followsWinding() {
        val ccw = PointBuffer().apply { add(0f, 0f); add(1f, 0f); add(1f, 1f); add(0f, 1f) }
        val cw = PointBuffer().apply { add(0f, 0f); add(0f, 1f); add(1f, 1f); add(1f, 0f) }

        assertEquals(1f, signedArea(ccw), 1e-6f)
        assertEquals(-1f, signedArea(cw), 1e-6f)
    }

    @Test
    fun fillPolygon_clampsFarSidePointsOntoTheLimb() {
        val points = floatArrayOf(
            0f, 0f, 1f,
            0.5f, 0f, -0.5f
        )
        val out = PointBuffer()
        buildFillPolygon(points, 2, cx = 0f, cy = 0f, r = 10f, out = out)

        assertEquals(0f to 0f, out.x(0) to out.y(0))
        assertEquals(10f, out.x(1), 1e-4f)
        assertEquals(0f, out.y(1), 1e-4f)
    }

    @Test
    fun polygonContains_distinguishesInsideAndOutsidePoints() {
        val polygon = PointBuffer().apply {
            add(-10f, -10f); add(10f, -10f); add(10f, 10f); add(-10f, 10f)
        }

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
