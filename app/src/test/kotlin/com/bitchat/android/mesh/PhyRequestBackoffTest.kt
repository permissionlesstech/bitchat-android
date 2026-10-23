package com.bitchat.android.mesh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhyRequestBackoffTest {

    private val coded = AdaptivePhyController.Target.CODED
    private val oneM = AdaptivePhyController.Target.ONE_M

    @Test
    fun `ignored requests are retried with growing gaps then stop`() {
        val backoff = PhyRequestBackoff(baseMs = 5_000L, maxMs = 40_000L, maxAttempts = 4)

        assertTrue(backoff.allow(coded, 0L))
        assertFalse(backoff.allow(coded, 4_000L))
        assertTrue(backoff.allow(coded, 5_000L))
        assertFalse(backoff.allow(coded, 14_000L))
        assertTrue(backoff.allow(coded, 15_000L))
        assertTrue(backoff.allow(coded, 35_000L))
        // Attempts exhausted: no more spam for this target.
        assertFalse(backoff.allow(coded, 1_000_000L))
    }

    @Test
    fun `reaching the target or changing target starts over`() {
        val backoff = PhyRequestBackoff(baseMs = 5_000L, maxMs = 40_000L, maxAttempts = 2)

        assertTrue(backoff.allow(coded, 0L))
        assertTrue(backoff.allow(coded, 5_000L))
        assertFalse(backoff.allow(coded, 100_000L))

        backoff.onPhy(isCoded = true)
        assertTrue(backoff.allow(coded, 100_001L))

        // A different target is a new situation.
        assertTrue(backoff.allow(oneM, 100_002L))
    }

    @Test
    fun `a phy report that does not match the target keeps the back-off`() {
        val backoff = PhyRequestBackoff(baseMs = 5_000L, maxMs = 40_000L, maxAttempts = 3)

        assertTrue(backoff.allow(coded, 0L))
        backoff.onPhy(isCoded = false)
        assertFalse(backoff.allow(coded, 1_000L))
    }
}
