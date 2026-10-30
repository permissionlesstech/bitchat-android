package com.bitchat.android.ui.globe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobeStateTest {

    private fun introState() = GlobeState(
        targetLat = 57.0,
        targetLon = 10.0,
        initialPrecision = 1,
        startZoomedOut = true
    ).apply { setViewport(baseRadiusPx = 475f, screenMinPx = 1080f) }

    @Test
    fun intro_startsSlightlySmallerThanTheWholeGlobeAtLevelOne() {
        val state = introState()

        assertTrue(state.zoom < GlobeMath.MIN_ZOOM)
        assertTrue(state.zoom > 0.5f)
        assertEquals(1, state.precision)
        assertEquals(1, state.selectedGeohash.length)
    }

    @Test
    fun wholeGlobeFrame_isLevelOne() {
        // The whole-globe button's frame is the level-1 frame.
        assertEquals(
            GlobeMath.MIN_ZOOM,
            GlobeMath.zoomForPrecision(1, baseRadiusPx = 475f, screenMinPx = 1080f)
        )
    }

    @Test
    fun intro_zoomsWellInButStaysBelowTwoLetterCells() {
        val state = introState()
        val end = state.introEndZoom()

        assertTrue("intro should zoom past the whole globe, was $end", end > 3f)
        assertEquals(1, GlobeMath.autoPrecision(475f * end, 1080f))
        assertTrue(end < GlobeMath.thresholdZoomForPrecision(2, 475f, 1080f))
    }

    @Test
    fun thresholdZoom_matchesAutoPrecisionOnBothSides() {
        for (precision in 2..6) {
            val threshold = GlobeMath.thresholdZoomForPrecision(precision, 475f, 1080f)
            assertEquals(precision, GlobeMath.autoPrecision(475f * threshold * 1.001f, 1080f))
            assertEquals(precision - 1, GlobeMath.autoPrecision(475f * threshold * 0.999f, 1080f))
        }
    }

    @Test
    fun pinchingAnInterruptedIntro_continuesWithoutSnapping() {
        val state = introState()
        val start = state.zoom

        state.zoomBy(1.1f)
        assertEquals(start * 1.1f, state.zoom, 1e-4f)

        state.zoomBy(0.5f)
        assertEquals(start * 1.1f, state.zoom, 1e-4f)
    }

    @Test
    fun zoomOut_isAvailableAfterTheOpeningZoomEvenAtLevelOne() {
        val state = introState()
        state.zoomBy(state.introEndZoom() / state.zoom)

        assertEquals(1, state.precision)
        assertTrue(state.canZoomOut)
    }

    @Test
    fun zoomOut_isUnavailableOnceTheWholeGlobeIsInView() {
        val wholeGlobe = GlobeState(57.0, 10.0, initialPrecision = 1, startZoomedOut = false)
            .apply { setViewport(475f, 1080f) }
        assertFalse(wholeGlobe.canZoomOut)

        // The opening rotation starts smaller than the whole globe; − has nothing to widen there.
        assertFalse(introState().canZoomOut)
    }

    @Test
    fun pinchingOut_stopsAtTheWholeGlobe() {
        val state = GlobeState(57.0, 10.0, initialPrecision = 1, startZoomedOut = false)
            .apply { setViewport(475f, 1080f) }

        state.zoomBy(0.5f)

        assertEquals(GlobeMath.MIN_ZOOM, state.zoom)
    }
}
