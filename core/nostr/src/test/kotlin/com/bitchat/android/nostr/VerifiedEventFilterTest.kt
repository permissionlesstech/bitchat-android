package com.bitchat.android.nostr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedEventFilterTest {

    private val alice = NostrIdentity.fromSeed("globe-filter-fixture-a")
    private val mallory = NostrIdentity.fromSeed("globe-filter-fixture-m")
    private val filter = VerifiedEventFilter()

    private fun heartbeat(identity: NostrIdentity, createdAt: Int = 1_800_000_000) = NostrEvent(
        pubkey = identity.publicKeyHex,
        createdAt = createdAt,
        kind = NostrKind.GEOHASH_PRESENCE,
        tags = listOf(listOf("g", "u0")),
        content = ""
    ).sign(identity.privateKeyHex)

    @Test
    fun `a validly signed event is admitted once`() {
        val event = heartbeat(alice)

        assertTrue(filter.admit(event))
        assertFalse(filter.admit(event))
    }

    @Test
    fun `a known id replayed with another pubkey is dropped unread`() {
        val event = heartbeat(alice)
        filter.admit(event)

        assertFalse(filter.admit(event.copy(pubkey = mallory.publicKeyHex)))
    }

    @Test
    fun `an unsigned or tampered event is rejected`() {
        val event = heartbeat(alice)

        assertFalse(filter.admit(event.copy(sig = null)))
        assertFalse(filter.admit(event.copy(createdAt = event.createdAt + 60)))
    }

    @Test
    fun `a forged copy sent first does not block the genuine event`() {
        val genuine = heartbeat(alice)

        assertFalse(filter.admit(genuine.copy(pubkey = mallory.publicKeyHex)))
        assertTrue(filter.admit(genuine))
    }

    @Test
    fun `a malformed id is rejected without throwing`() {
        val event = heartbeat(alice).copy(id = "not-hex-" + "z".repeat(56))

        assertFalse(filter.admit(event))
    }

    @Test
    fun `admitted ids are bounded`() {
        val small = VerifiedEventFilter(capacity = 1)
        val first = heartbeat(alice, createdAt = 1_800_000_000)
        val second = heartbeat(alice, createdAt = 1_800_000_001)

        assertTrue(small.admit(first))
        assertTrue(small.admit(second))
        assertTrue(small.admit(first))
    }
}
