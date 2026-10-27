package com.bitchat.android.ui.globe

import com.bitchat.android.nostr.GeohashChannelActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobeMarkerAnimatorTest {

    private val ms = 1_000_000L

    private fun channel(geohash: String, people: Int = 3) =
        GeohashChannelActivity(geohash, 0.0, 0.0, people, 0, 0, people, 0L)

    private fun marker(x: Float, y: Float, radius: Float, vararg geohashes: String) = GlobeMarker(
        x = x,
        y = y,
        radius = radius,
        channels = geohashes.map { channel(it) },
        people = geohashes.size,
        messages = 0,
        lastActiveAt = 0L
    )

    @Test
    fun newMarker_popsInAndSettlesAtFullSize() {
        val animator = GlobeMarkerAnimator()
        val a = marker(100f, 100f, 10f, "u0")

        val first = animator.frame(listOf(a), 0L)
        assertTrue(first.all { it.radius < 10f })
        assertTrue(animator.isAnimating)

        val settled = animator.frame(listOf(a), 600 * ms).single()
        assertEquals(10f, settled.radius, 0.01f)
        assertEquals(1f, settled.alpha, 0.01f)
        animator.frame(listOf(a), 616 * ms)
        assertFalse(animator.isAnimating)
    }

    @Test
    fun removedMarker_shrinksOutThenDisappears() {
        val animator = GlobeMarkerAnimator()
        val a = marker(100f, 100f, 10f, "u0")
        animator.frame(listOf(a), 0L)
        animator.frame(listOf(a), 600 * ms)

        animator.frame(emptyList(), 600 * ms)
        val leaving = animator.frame(emptyList(), 680 * ms).single()
        assertTrue(leaving.radius in 0.1f..9.99f)
        assertTrue(leaving.alpha < 1f)

        assertTrue(animator.frame(emptyList(), 800 * ms).isEmpty())
        assertFalse(animator.isAnimating)
    }

    @Test
    fun mergedMarker_fliesIntoItsCluster() {
        val animator = GlobeMarkerAnimator()
        val a = marker(100f, 100f, 10f, "u0")
        val b = marker(200f, 100f, 10f, "u1")
        animator.frame(listOf(a, b), 0L)
        animator.frame(listOf(a, b), 600 * ms)

        val cluster = marker(100f, 100f, 14f, "u0", "u1")
        animator.frame(listOf(cluster), 610 * ms)
        val ghost = animator.frame(listOf(cluster), 720 * ms).first { it.marker.primary.geohash == "u1" }

        assertTrue("ghost should travel toward the cluster", ghost.x < 200f)
    }

    @Test
    fun splitMarker_startsFromItsCluster() {
        val animator = GlobeMarkerAnimator()
        val cluster = marker(100f, 100f, 14f, "u0", "u1")
        animator.frame(listOf(cluster), 0L)
        animator.frame(listOf(cluster), 600 * ms)

        val a = marker(100f, 100f, 10f, "u0")
        val b = marker(200f, 100f, 10f, "u1")
        animator.frame(listOf(a, b), 616 * ms)
        val early = animator.frame(listOf(a, b), 640 * ms).first { it.marker.primary.geohash == "u1" }
        assertTrue("split marker starts near the cluster", early.x < 150f)

        val settled = animator.frame(listOf(a, b), 1_200 * ms).first { it.marker.primary.geohash == "u1" }
        assertEquals(200f, settled.x, 0.01f)
    }

    @Test
    fun clusterGrowth_easesInsteadOfJumping() {
        val animator = GlobeMarkerAnimator()
        animator.frame(listOf(marker(100f, 100f, 10f, "u0")), 0L)
        animator.frame(listOf(marker(100f, 100f, 10f, "u0")), 600 * ms)

        val grown = animator.frame(listOf(marker(100f, 100f, 16f, "u0")), 616 * ms).single()

        assertTrue(grown.radius in 10.01f..15.99f)
        assertTrue(animator.isAnimating)
    }

    @Test
    fun removeAnimations_makesEveryChangeInstant() {
        val animator = GlobeMarkerAnimator(durationScale = 0f)
        val a = marker(100f, 100f, 10f, "u0")

        assertEquals(10f, animator.frame(listOf(a), 0L).single().radius, 0.01f)
        assertTrue(animator.frame(emptyList(), 16 * ms).isEmpty())
        assertFalse(animator.isAnimating)
    }

    @Test
    fun markersFadeTowardTheLimb() {
        assertEquals(0f, limbFade(0f), 0f)
        assertTrue(limbFade(0.1f) in 0.01f..0.99f)
        assertEquals(1f, limbFade(0.5f), 0f)
    }

    @Test
    fun popIn_overshootsThenLands() {
        assertEquals(0f, easeOutBack(0f), 0.001f)
        assertTrue((1..9).any { easeOutBack(it / 10f) > 1f })
        assertEquals(1f, easeOutBack(1f), 0.001f)
    }
}
