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

        assertTrue(detail.showGraticule)
        assertEquals(1, detail.landPointStride)
        assertEquals(0f, detail.minimumLandRingRadiusPx)
        assertTrue(detail.showBorders)
        assertNull(detail.cityMaxRank)
        assertTrue(detail.showCityLabels)
        assertTrue(detail.showStateLabels)
        assertNull(detail.maximumBoundaryLabels)
        assertTrue(detail.showGeohashGrid)
        assertTrue(detail.showNeighborCells)
    }

    @Test
    fun fastDetail_preservesTheCompleteVisualDesignDuringMovement() {
        val detail = globeFrameDetail(GlobeMotionDetail.FAST)

        assertTrue(detail.showGraticule)
        assertEquals(1, detail.landPointStride)
        assertEquals(0f, detail.minimumLandRingRadiusPx)
        assertTrue(detail.showBorders)
        assertNull(detail.cityMaxRank)
        assertTrue(detail.showCityLabels)
        assertTrue(detail.showStateLabels)
        assertNull(detail.maximumBoundaryLabels)
        assertTrue(detail.showGeohashGrid)
        assertTrue(detail.showNeighborCells)
        assertEquals(globeFrameDetail(GlobeMotionDetail.FULL), detail)
    }

    @Test
    fun labelTransition_keepsCurrentOrderThenFadesRemovedPlaces() {
        val retainedBefore = city("Retained", 12f, 34f)
        val removed = city("Removed", 20f, 40f)
        val retainedAfter = city("Retained", 12f, 34f)
        val added = city("Added", 30f, 50f)

        val transition = buildMapLabelTransition(
            previous = listOf(retainedBefore, removed),
            current = listOf(retainedAfter, added)
        )

        assertTrue(transition.hasChanges)
        assertEquals(
            listOf("Retained", "Added", "Removed"),
            transition.labels.map { it.label.name }
        )
        assertEquals(
            listOf(
                MapLabelTransitionPhase.STABLE,
                MapLabelTransitionPhase.ENTERING,
                MapLabelTransitionPhase.EXITING
            ),
            transition.labels.map { it.phase }
        )
    }

    @Test
    fun labelTransition_matchesRedecodedLabelsByContent() {
        val transition = buildMapLabelTransition(
            previous = listOf(city("Stable", 12f, 34f)),
            current = listOf(city("Stable", 12f, 34f))
        )

        assertFalse(transition.hasChanges)
        assertEquals(MapLabelTransitionPhase.STABLE, transition.labels.single().phase)
    }

    private fun city(name: String, lat: Float, lon: Float) = MapLabel(
        name = name,
        lat = lat,
        lon = lon,
        kind = MapLabelKind.CITY,
        importance = 100_000L
    )
}
