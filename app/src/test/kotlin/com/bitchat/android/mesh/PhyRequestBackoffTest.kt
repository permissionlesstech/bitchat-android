package com.bitchat.android.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhyRequestBackoffTest {

    private val coded = AdaptivePhyController.Target.CODED
    private val oneM = AdaptivePhyController.Target.ONE_M

    @Test
    fun `ignored requests are retried with growing gaps then pause`() {
        val backoff = PhyRequestBackoff(baseMs = 5_000L, maxMs = 40_000L, maxAttempts = 4, cooldownMs = 300_000L)

        assertTrue(backoff.allow(coded, 0L))
        assertFalse(backoff.allow(coded, 4_000L))
        assertTrue(backoff.allow(coded, 5_000L))
        assertFalse(backoff.allow(coded, 14_000L))
        assertTrue(backoff.allow(coded, 15_000L))
        assertTrue(backoff.allow(coded, 35_000L))
        // Attempts exhausted: no more requests for this target during the pause.
        assertFalse(backoff.allow(coded, 200_000L))
        // After the pause a new round starts: the remote may have changed its mind.
        assertTrue(backoff.allow(coded, 335_000L))
        assertFalse(backoff.allow(coded, 336_000L))
    }

    @Test
    fun `refusal note explains requests the peer has not followed`() {
        val backoff = PhyRequestBackoff(baseMs = 5_000L, maxMs = 40_000L, maxAttempts = 2, cooldownMs = 300_000L)
        assertNull(backoff.refusalNote(oneM, 0L))

        assertTrue(backoff.allow(oneM, 0L))
        assertEquals("1M requested 1x, peer hasn't switched yet", backoff.refusalNote(oneM, 1_000L))
        assertNull(backoff.refusalNote(coded, 1_000L))

        assertTrue(backoff.allow(oneM, 10_000L))
        assertEquals(
            "peer kept its PHY after 2 requests for 1M; asking again in 290s",
            backoff.refusalNote(oneM, 20_000L)
        )

        backoff.onPhy(isCoded = false)
        assertNull(backoff.refusalNote(oneM, 21_000L))
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
