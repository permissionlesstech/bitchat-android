package com.bitchat.android.ui.globe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobeInteractionPolicyTest {

    @Test
    fun fullDetail_preservesAllGlobeFeatures() {
        val detail = globeFrameDetail(GlobeMotionDetail.FULL)

        assertEquals(1, detail.landPointStride)
        assertTrue(detail.showBorders)
        assertTrue(detail.showCityLabels)
    }

    @Test
    fun balancedDetail_keepsBordersButDropsLabels() {
        val detail = globeFrameDetail(GlobeMotionDetail.BALANCED)

        assertEquals(2, detail.landPointStride)
        assertTrue(detail.showBorders)
        assertFalse(detail.showCityLabels)
    }

    @Test
    fun fastDetail_usesMinimumMovingFrameWork() {
        val detail = globeFrameDetail(GlobeMotionDetail.FAST)

        assertEquals(2, detail.landPointStride)
        assertFalse(detail.showBorders)
        assertFalse(detail.showCityLabels)
    }

    @Test
    fun adaptiveDetail_wantedLevelFollowsCadenceWithHysteresis() {
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

    private fun GlobeMotionGovernor.frames(count: Int, millis: Float) =
        repeat(count) { recordFrame(millis) }

    @Test
    fun governor_neverStepsBackUpWithinAGesture() {
        val governor = GlobeMotionGovernor()
        governor.beginSession()
        assertEquals(GlobeMotionDetail.BALANCED, governor.detail)

        governor.frames(8, millis = 32f)
        assertEquals(GlobeMotionDetail.FAST, governor.detail)

        // Shedding detail made frames fast again; detail must not flash back up mid-gesture.
        governor.frames(32, millis = 12f)
        assertEquals(GlobeMotionDetail.FAST, governor.detail)
    }

    @Test
    fun governor_restoresFullDetailAtRestAndRemembersTheCeiling() {
        val governor = GlobeMotionGovernor()
        governor.beginSession()
        governor.frames(8, millis = 32f)
        governor.endSession()
        assertEquals(GlobeMotionDetail.FULL, governor.detail)

        governor.beginSession()
        assertEquals(GlobeMotionDetail.FAST, governor.detail)
        governor.frames(16, millis = 12f)
        governor.endSession()

        // A ceiling was found, so a smooth gesture does not promote the next one.
        governor.beginSession()
        assertEquals(GlobeMotionDetail.FAST, governor.detail)
    }

    @Test
    fun governor_smoothGesturesLetTheNextStartHigher() {
        val governor = GlobeMotionGovernor()
        governor.beginSession()
        governor.frames(16, millis = 12f)
        governor.endSession()

        governor.beginSession()
        assertEquals(GlobeMotionDetail.FULL, governor.detail)
    }
}
