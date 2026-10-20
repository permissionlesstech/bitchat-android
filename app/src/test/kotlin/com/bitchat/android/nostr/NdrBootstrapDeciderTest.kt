package com.bitchat.android.nostr

import org.junit.Assert.assertEquals
import org.junit.Test

class NdrBootstrapDeciderTest {

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
