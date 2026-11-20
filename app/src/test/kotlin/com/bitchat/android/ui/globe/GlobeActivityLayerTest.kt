package com.bitchat.android.ui.globe

import androidx.compose.ui.geometry.Offset
import com.bitchat.android.nostr.GeohashChannelActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobeActivityLayerTest {

    private fun channel(geohash: String, lat: Double, lon: Double, people: Int, messages: Int = 0) =
        GeohashChannelActivity(geohash, lat, lon, people, 0, messages, people, 0L)

    private fun layout(channels: List<GeohashChannelActivity>, zoomRadius: Float = 400f) =
        layoutGlobeMarkers(
            channels = channels,
            projector = GlobeMath.PreparedProjector(0.0, 0.0),
            cx = 500f,
            cy = 500f,
            r = zoomRadius,
            width = 1000f,
            height = 1000f,
            density = 1f
        )

    @Test
    fun overlappingChannels_mergeIntoOneCluster() {
        val markers = layout(listOf(channel("s00", 0.0, 0.0, 9, 4), channel("s01", 0.2, 0.2, 1)))

        val cluster = markers.single()
        assertTrue(cluster.isCluster)
        assertEquals("s00", cluster.primary.geohash)
        assertEquals(4, cluster.messages)
    }

    @Test
    fun clusterSplits_whenZoomedIn() {
        val channels = listOf(channel("s00", 0.0, 0.0, 9), channel("s01", 0.2, 0.2, 1))

        assertEquals(2, layout(channels, zoomRadius = 40_000f).size)
    }

    @Test
    fun farSideChannels_areNotDrawn() {
        assertTrue(layout(listOf(channel("8", 0.0, 180.0, 50))).isEmpty())
    }

    @Test
    fun hitTest_picksMarkerUnderFingerWithinSlop() {
        val markers = layout(listOf(channel("s00", 0.0, 0.0, 4), channel("t00", 0.0, 40.0, 4)))
        val markerLayout = GlobeMarkerLayout().apply { this.markers = markers }

        assertSame(markers.first { it.primary.geohash == "s00" }, markerLayout.hitTest(Offset(503f, 500f), 8f))
        assertNull(markerLayout.hitTest(Offset(500f, 900f), 8f))
    }

    @Test
    fun chatMarkers_outgrowHeartbeatOnlyMarkers() {
        assertTrue(markerRadiusDp(4, chatting = true, cluster = false) > markerRadiusDp(4, chatting = false, cluster = false))
        assertTrue(markerRadiusDp(400, chatting = true, cluster = false) <= 17f)
    }

    @Test
    fun heat_risesWithMessagesAndSaturates() {
        assertEquals(0f, markerHeat(0))
        assertTrue(markerHeat(5) in 0f..1f)
        assertEquals(1f, markerHeat(10_000))
    }

    @Test
    fun compactCount_shortensLargeNumbers() {
        assertEquals("7", compactCount(7))
        assertEquals("842", compactCount(842))
        assertEquals("1.2k", compactCount(1_234))
        assertEquals("2k", compactCount(2_010))
        assertEquals("12k", compactCount(12_400))
    }
}
