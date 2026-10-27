package com.bitchat.android.ui.globe

import org.junit.Assert.assertEquals
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
        // The intro lands on the level-1 frame, which is the whole-globe view.
        assertEquals(
            GlobeMath.MIN_ZOOM,
            GlobeMath.zoomForPrecision(1, baseRadiusPx = 475f, screenMinPx = 1080f)
        )
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
    fun pinchingOut_stopsAtTheWholeGlobe() {
        val state = GlobeState(57.0, 10.0, initialPrecision = 1, startZoomedOut = false)
            .apply { setViewport(475f, 1080f) }

        state.zoomBy(0.5f)

        assertEquals(GlobeMath.MIN_ZOOM, state.zoom)
    }
}
