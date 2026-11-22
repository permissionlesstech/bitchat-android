package com.bitchat.android.nostr

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class NostrRelayAuthenticationTest {
    @Test
    fun `authentication proof matches the literal NIP-01 hash and verifies with the account key`() {
        val identity = NostrIdentity.fromPrivateKey("0".repeat(63) + "1")
        val proof = NostrEvent.createRelayAuthentication(
            "wss://relay.example/", "synthetic-challenge", identity, 1_700_000_000
        )

        assertEquals("7bd62f9e67246e6467dac414432c90e99694055a08ffd1b8dbfcf4f07788cba3", proof.id)
        assertEquals(identity.publicKeyHex, proof.pubkey)
        assertEquals(22242, proof.kind)
        assertEquals("", proof.content)
        assertTrue(proof.isValidSignature())
        val wire = NostrRequest.createGson().toJson(NostrRequest.Auth(proof), NostrRequest::class.java)
        val frame = JsonParser.parseString(wire).asJsonArray
        assertEquals("AUTH", frame[0].asString)
        assertEquals(proof.id, frame[1].asJsonObject["id"].asString)
        assertEquals(1_700_000_000, frame[1].asJsonObject["created_at"].asInt)
        assertEquals(
            "[[\"relay\",\"wss://relay.example/\"],[\"challenge\",\"synthetic-challenge\"]]",
            frame[1].asJsonObject["tags"].toString()
        )
    }

    @Test
    fun `challenge advertising support does not disclose identity until required`() {
        val auth = NostrRelayAuthentication()
        assertNull(auth.onChallenge("challenge").challenge)
        assertEquals("challenge", auth.onSubscriptionRejected("sub").challenge)
        assertNull(auth.onSubscriptionRejected("sub").challenge)
        assertNull(auth.onEventRejected("event").challenge)
        assertTrue(auth.recordProof("challenge", "proof"))

        assertFalse(auth.onOk("unrelated", true).authResponse)
        val accepted = auth.onOk("proof", true)
        assertTrue(accepted.authResponse)
        assertEquals(setOf("sub"), accepted.subscriptionIds)
        assertEquals(setOf("event"), accepted.eventIds)
        assertTrue(auth.onSubscriptionRejected("sub").subscriptionIds.isEmpty())
        assertTrue(auth.onEventRejected("event").eventIds.isEmpty())
    }

    @Test
    fun `rejection arriving before the challenge is retried only after matching successful proof`() {
        val auth = NostrRelayAuthentication()
        assertNull(auth.onEventRejected("event").challenge)
        assertEquals("challenge", auth.onChallenge("challenge").challenge)
        assertTrue(auth.recordProof("challenge", "proof"))
        assertTrue(auth.onOk("other-proof", true).eventIds.isEmpty())
        assertEquals(setOf("event"), auth.onOk("proof", true).eventIds)
        assertFalse(auth.onOk("proof", true).authResponse)
    }

    @Test
    fun `denied identity and repeated challenges never cause an authentication loop`() {
        val auth = NostrRelayAuthentication()
        auth.onChallenge("challenge")
        auth.onEventRejected("event")
        auth.recordProof("challenge", "proof")
        val denied = auth.onOk("proof", false)
        assertTrue(denied.rejected)
        assertTrue(denied.eventIds.isEmpty())
        assertNull(auth.onChallenge("challenge").challenge)
        assertNull(auth.onEventRejected("event").challenge)
        assertNull(auth.onSubscriptionRejected("sub").challenge)
    }

    @Test
    fun `a rotated challenge invalidates the old proof and replay guards`() {
        val auth = NostrRelayAuthentication()
        auth.onChallenge("old")
        auth.onSubscriptionRejected("sub")
        auth.recordProof("old", "old-proof")
        assertEquals("new", auth.onChallenge("new").challenge)
        assertFalse(auth.recordProof("old", "obsolete"))
        assertFalse(auth.onOk("old-proof", true).authResponse)
        assertTrue(auth.recordProof("new", "new-proof"))
        assertEquals(setOf("sub"), auth.onOk("new-proof", true).subscriptionIds)
    }

    @Test
    fun `late auth-required rejection is replayed once on an authenticated session`() {
        val auth = NostrRelayAuthentication()
        auth.onChallenge("challenge")
        auth.onSubscriptionRejected("first")
        auth.recordProof("challenge", "proof")
        auth.onOk("proof", true)
        assertEquals(setOf("late"), auth.onSubscriptionRejected("late").subscriptionIds)
        assertEquals(setOf("event"), auth.onEventRejected("event").eventIds)
        assertTrue(auth.onSubscriptionRejected("late").subscriptionIds.isEmpty())
        assertTrue(auth.onEventRejected("event").eventIds.isEmpty())
    }

    @Test
    fun `unsubscribe and privacy purge remove blocked operations before auth completes`() {
        val auth = NostrRelayAuthentication()
        auth.onChallenge("challenge")
        auth.onSubscriptionRejected("removed")
        auth.onSubscriptionRejected("retained")
        auth.onEventRejected("revoked")
        auth.onEventRejected("manual")
        auth.recordProof("challenge", "proof")
        auth.forgetSubscription("removed")
        auth.forgetEvent("revoked")

        val accepted = auth.onOk("proof", true)
        assertEquals(setOf("retained"), accepted.subscriptionIds)
        assertEquals(setOf("manual"), accepted.eventIds)
    }

    @Test
    fun `fresh connection requires its own challenge and limits challenge churn`() {
        val auth = NostrRelayAuthentication()
        auth.onEventRejected("event")
        repeat(3) { index ->
            assertEquals("challenge-$index", auth.onChallenge("challenge-$index").challenge)
        }
        assertNull(auth.onChallenge("excess").challenge)
        assertNull(auth.onChallenge("x".repeat(4097)).challenge)
        val reconnected = NostrRelayAuthentication()
        assertNull(reconnected.onEventRejected("event").challenge)
        assertEquals("fresh", reconnected.onChallenge("fresh").challenge)
    }

    @Test
    fun `malformed authentication frames cannot be coerced into successful responses`() {
        listOf("[\"AUTH\",42]", "[\"AUTH\",null]", "[\"AUTH\"]",
            "[\"CLOSED\",\"sub\",true]", "[\"OK\",\"proof\",\"true\",\"\"]",
            "[\"OK\",\"proof\",1,\"\"]").forEach { wire ->
            assertTrue(NostrResponse.fromJsonArray(JsonParser.parseString(wire).asJsonArray) is NostrResponse.Unknown)
        }
        assertEquals(
            NostrResponse.Auth("challenge"),
            NostrResponse.fromJsonArray(JsonParser.parseString("[\"AUTH\",\"challenge\"]").asJsonArray)
        )
        assertTrue(NostrRelayAuthentication.isAuthRequired("auth-required: authenticate"))
        assertFalse(NostrRelayAuthentication.isAuthRequired("restricted: not a member"))
    }
}
