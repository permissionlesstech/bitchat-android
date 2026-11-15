package com.bitchat.android.nostr

import org.junit.Assert.*
import org.junit.Test

class NostrRelayAuthenticationSessionTest {
    private val first = NostrIdentity.fromPrivateKey("0".repeat(63) + "1")
    private val second = NostrIdentity.fromPrivateKey("0".repeat(63) + "2")

    @Test
    fun `accepting one key does not authorize or replay a different key's operations`() {
        val session = NostrRelayAuthenticationSession()
        session.onChallenge("synthetic-challenge")
        session.onSubscriptionRejected("first", first)
        session.onEventRejected(1L, first)
        session.onSubscriptionRejected("second", second)
        session.onEventRejected(2L, second)
        assertTrue(session.recordProof(first, "synthetic-challenge", "first-proof"))
        assertTrue(session.recordProof(second, "synthetic-challenge", "second-proof"))

        val accepted = requireNotNull(session.onOk("first-proof", true))
        assertEquals(first.publicKeyHex, accepted.identity.publicKeyHex)
        assertEquals(setOf("first"), accepted.actions.subscriptionIds)
        assertEquals(setOf("1"), accepted.actions.eventIds)
        val denied = requireNotNull(session.onOk("second-proof", false))
        assertTrue(denied.actions.rejected)
        assertTrue(denied.actions.subscriptionIds.isEmpty())
        assertTrue(denied.actions.eventIds.isEmpty())
    }

    @Test
    fun `same signed publication retains separate delivery contexts for distinct identities`() {
        val session = NostrRelayAuthenticationSession()
        session.onEventRejected(1L, first)
        session.onEventRejected(2L, second)
        val proofs = session.onChallenge("synthetic-challenge")
        assertEquals(setOf(first.publicKeyHex, second.publicKeyHex), proofs.map { it.identity.publicKeyHex }.toSet())
        assertTrue(session.recordProof(first, "synthetic-challenge", "first-proof"))
        assertTrue(session.recordProof(second, "synthetic-challenge", "second-proof"))
        session.forgetEvent(1L)
        assertTrue(requireNotNull(session.onOk("first-proof", true)).actions.eventIds.isEmpty())
        assertEquals(setOf("2"), requireNotNull(session.onOk("second-proof", true)).actions.eventIds)
    }

    @Test
    fun `revoked operations cannot initiate identity disclosure on a later challenge`() {
        val session = NostrRelayAuthenticationSession()
        session.onSubscriptionRejected("revoked", first)
        session.onEventRejected(1L, second)
        session.forgetSubscription("revoked")
        session.forgetEvent(1L)
        assertTrue(session.onChallenge("synthetic-challenge").all { it.actions.challenge == null })
        assertNull(session.onOk("foreign-proof", true))
    }
}
