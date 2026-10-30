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
    fun newMarker_fadesInInPlaceAndSettlesAtFullSize() {
        val animator = GlobeMarkerAnimator()
        val a = marker(100f, 100f, 10f, "u0")

        animator.frame(listOf(a), 0L)
        val appearing = animator.frame(listOf(a), 80 * ms).single()
        assertEquals(100f, appearing.x, 0f)
        assertEquals(100f, appearing.y, 0f)
        assertTrue(appearing.alpha in 0.01f..0.99f)
        assertTrue(appearing.radius in 6f..9.99f)
        assertTrue(animator.isAnimating)

        val settled = animator.frame(listOf(a), 250 * ms).single()
        assertEquals(10f, settled.radius, 0.01f)
        assertEquals(1f, settled.alpha, 0.01f)
        animator.frame(listOf(a), 266 * ms)
        assertFalse(animator.isAnimating)
    }

    @Test
    fun appearing_neverOvershootsFullSize() {
        val animator = GlobeMarkerAnimator()
        val a = marker(100f, 100f, 10f, "u0")

        for (t in 0..250 step 8) {
            assertTrue(animator.frame(listOf(a), t * ms).single().radius <= 10f)
        }
    }

    @Test
    fun removedMarker_fadesOutWhereItWasThenDisappears() {
        val animator = GlobeMarkerAnimator()
        val a = marker(100f, 100f, 10f, "u0")
        animator.frame(listOf(a), 0L)
        animator.frame(listOf(a), 300 * ms)

        animator.frame(emptyList(), 300 * ms)
        val leaving = animator.frame(emptyList(), 370 * ms).single()
        assertEquals(100f, leaving.x, 0f)
        assertTrue(leaving.radius in 7f..9.99f)
        assertTrue(leaving.alpha in 0.01f..0.99f)

        assertTrue(animator.frame(emptyList(), 460 * ms).isEmpty())
        assertFalse(animator.isAnimating)
    }

    @Test
    fun mergingIntoACluster_fadesInPlaceWithoutTravelling() {
        val animator = GlobeMarkerAnimator()
        val a = marker(100f, 100f, 10f, "u0")
        val b = marker(200f, 100f, 10f, "u1")
        animator.frame(listOf(a, b), 0L)
        animator.frame(listOf(a, b), 300 * ms)

        val cluster = marker(100f, 100f, 14f, "u0", "u1")
        animator.frame(listOf(cluster), 310 * ms)
        val ghost = animator.frame(listOf(cluster), 360 * ms).first { it.marker.primary.geohash == "u1" }

        assertEquals(200f, ghost.x, 0f)
    }

    @Test
    fun clusterGrowth_easesInsteadOfJumping() {
        val animator = GlobeMarkerAnimator()
        animator.frame(listOf(marker(100f, 100f, 10f, "u0")), 0L)
        animator.frame(listOf(marker(100f, 100f, 10f, "u0")), 300 * ms)

        val grown = animator.frame(listOf(marker(100f, 100f, 16f, "u0")), 316 * ms).single()

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
}
