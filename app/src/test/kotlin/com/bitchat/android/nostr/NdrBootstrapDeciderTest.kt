package com.bitchat.android.nostr

import org.junit.Assert.assertEquals
import org.junit.Test

class NdrBootstrapDeciderTest {

    @Test
    fun authenticatedIdentitiesElectExactlyOneInitiator() {
        val lower = "79".repeat(32)
        val higher = "c6".repeat(32)
        assertEquals(true, NdrBootstrapDecider.shouldInitiate(lower, higher))
        assertEquals(false, NdrBootstrapDecider.shouldInitiate(higher, lower))
        assertEquals(false, NdrBootstrapDecider.shouldInitiate(lower, lower))
        assertEquals(true, NdrBootstrapDecider.shouldInitiate(lower, higher.uppercase()))
        assertEquals(false, NdrBootstrapDecider.shouldInitiate("", higher))
        assertEquals(false, NdrBootstrapDecider.shouldInitiate(lower, "g".repeat(64)))
    }

    @Test
    fun activeRatchetDoesNothing() {
        assertEquals(
            NdrBootstrapAction.NONE,
            NdrBootstrapDecider.decide(
                hasActiveDoubleRatchet = true,
                hasEstablishedNoiseSession = true,
                nowMs = 30_000,
                lastInviteAttemptMs = 0,
                lastHandshakeAttemptMs = 0
            )
        )
    }

    @Test
    fun missingNoiseSessionStartsHandshakeBeforeInvite() {
        assertEquals(
            NdrBootstrapAction.START_NOISE_HANDSHAKE,
            NdrBootstrapDecider.decide(
                hasActiveDoubleRatchet = false,
                hasEstablishedNoiseSession = false,
                nowMs = 5_000,
                lastInviteAttemptMs = 0,
                lastHandshakeAttemptMs = 0
            )
        )
    }

    @Test
    fun establishedNoiseSessionSendsInviteAndThrottlesRetries() {
        assertEquals(
            NdrBootstrapAction.SEND_OOB_INVITE,
            NdrBootstrapDecider.decide(
                hasActiveDoubleRatchet = false,
                hasEstablishedNoiseSession = true,
                nowMs = 15_000,
                lastInviteAttemptMs = 0,
                lastHandshakeAttemptMs = 0
            )
        )
        assertEquals(
            NdrBootstrapAction.NONE,
            NdrBootstrapDecider.decide(
                hasActiveDoubleRatchet = false,
                hasEstablishedNoiseSession = true,
                nowMs = 20_000,
                lastInviteAttemptMs = 15_000,
                lastHandshakeAttemptMs = 0
            )
        )
    }
}
