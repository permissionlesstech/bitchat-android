package com.bitchat.android.ui.globe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobeInteractionPolicyTest {

    @Test
    fun fullDetail_preservesAllGlobeFeatures() {
        val detail = globeFrameDetail(GlobeMotionDetail.FULL)

        assertEquals(1, detail.landPointStride)
        assertTrue(detail.showBorders)
        assertNull(detail.cityMaxRank)
        assertTrue(detail.showCityLabels)
        assertTrue(detail.showGeohashGrid)
        assertTrue(detail.showNeighborCells)
    }

    @Test
    fun balancedDetail_preservesOrientationAndSelection() {
        val detail = globeFrameDetail(GlobeMotionDetail.BALANCED)

        assertEquals(2, detail.landPointStride)
        assertTrue(detail.showBorders)
        assertEquals(1, detail.cityMaxRank)
        assertFalse(detail.showCityLabels)
        assertTrue(detail.showGeohashGrid)
        assertFalse(detail.showNeighborCells)
    }

    @Test
    fun fastDetail_usesMinimumMovingFrameWork() {
        val detail = globeFrameDetail(GlobeMotionDetail.FAST)

        assertEquals(2, detail.landPointStride)
        assertFalse(detail.showBorders)
        assertEquals(-1, detail.cityMaxRank)
        assertFalse(detail.showCityLabels)
        assertFalse(detail.showGeohashGrid)
    }

    @Test
    fun adaptiveDetail_degradesOnSlowFramesAndRecoversWithHysteresis() {
        assertEquals(
            GlobeMotionDetail.BALANCED,
            nextGlobeMotionDetail(GlobeMotionDetail.FULL, averageFrameMillis = 24f)
        )
        assertEquals(
            GlobeMotionDetail.FAST,
            nextGlobeMotionDetail(GlobeMotionDetail.BALANCED, averageFrameMillis = 32f)
        )
        assertEquals(
            GlobeMotionDetail.BALANCED,
            nextGlobeMotionDetail(GlobeMotionDetail.FAST, averageFrameMillis = 17f)
        )
        assertEquals(
            GlobeMotionDetail.FULL,
            nextGlobeMotionDetail(GlobeMotionDetail.BALANCED, averageFrameMillis = 17f)
        )
    }
}
