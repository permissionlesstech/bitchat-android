package com.bitchat.android.ui.globe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobeRenderQualityTest {

    @Test
    fun invalidStoredValue_defaultsToMedium() {
        assertEquals(GlobeRenderQuality.MEDIUM, GlobeRenderQuality.fromStoredValue(null))
        assertEquals(GlobeRenderQuality.MEDIUM, GlobeRenderQuality.fromStoredValue("UNKNOWN"))
    }

    @Test
    fun globeState_usesAndUpdatesRenderQualityWithoutReplacingState() {
        val state = GlobeState(
            targetLat = 0.0,
            targetLon = 0.0,
            initialPrecision = 2,
            startZoomedOut = false,
            initialRenderQuality = GlobeRenderQuality.FAST
        )

        assertEquals(GlobeRenderQuality.FAST, state.renderQuality)
        state.setRenderQuality(GlobeRenderQuality.HIGH)
        assertEquals(GlobeRenderQuality.HIGH, state.renderQuality)
    }

    @Test
    fun stationaryFrame_alwaysUsesFullDetail() {
        GlobeRenderQuality.entries.forEach { quality ->
            val detail = globeFrameDetail(quality, isMoving = false)

            assertEquals(1, detail.landPointStride)
            assertTrue(detail.showBorders)
            assertNull(detail.cityMaxRank)
            assertTrue(detail.showCityLabels)
            assertTrue(detail.showGeohashGrid)
            assertTrue(detail.showNeighborCells)
        }
    }

    @Test
    fun movingFastFrame_usesMinimumDetail() {
        val detail = globeFrameDetail(GlobeRenderQuality.FAST, isMoving = true)

        assertEquals(2, detail.landPointStride)
        assertFalse(detail.showBorders)
        assertEquals(-1, detail.cityMaxRank)
        assertFalse(detail.showGeohashGrid)
    }

    @Test
    fun movingMediumFrame_preservesOrientationAndSelection() {
        val detail = globeFrameDetail(GlobeRenderQuality.MEDIUM, isMoving = true)

        assertEquals(2, detail.landPointStride)
        assertTrue(detail.showBorders)
        assertEquals(1, detail.cityMaxRank)
        assertFalse(detail.showCityLabels)
        assertTrue(detail.showGeohashGrid)
        assertFalse(detail.showNeighborCells)
    }

    @Test
    fun movingHighFrame_usesFullDetail() {
        val detail = globeFrameDetail(GlobeRenderQuality.HIGH, isMoving = true)

        assertEquals(1, detail.landPointStride)
        assertTrue(detail.showBorders)
        assertNull(detail.cityMaxRank)
        assertTrue(detail.showCityLabels)
        assertTrue(detail.showNeighborCells)
    }
}
