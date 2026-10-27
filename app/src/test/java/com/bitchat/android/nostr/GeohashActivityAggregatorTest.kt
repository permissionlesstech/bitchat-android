package com.bitchat.android.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeohashActivityAggregatorTest {

    private val now = 1_800_000_000L
    private val aggregator = GeohashActivityAggregator()

    private fun chat(id: String, pubkey: String, geohash: String = "u0", at: Long = now, content: String = "hi") =
        aggregator.record(NostrKind.EPHEMERAL_EVENT, id, pubkey, geohash, at, content, now)

    private fun heartbeat(id: String, pubkey: String, geohash: String = "u0", at: Long = now) =
        aggregator.record(NostrKind.GEOHASH_PRESENCE, id, pubkey, geohash, at, "", now)

    @Test
    fun `chat and heartbeats fold into one channel with distinct people`() {
        chat("e1", "alice")
        chat("e2", "alice")
        chat("e3", "bob")
        heartbeat("e4", "carol")
        heartbeat("e5", "alice")

        val channel = aggregator.snapshot(now).channel("u0")!!

        assertEquals(3, channel.people)
        assertEquals(2, channel.chatters)
        assertEquals(3, channel.messages)
        assertEquals(3, channel.online)
    }

    @Test
    fun `duplicate event ids count once`() {
        assertTrue(chat("e1", "alice"))
        assertFalse(chat("e1", "alice"))

        assertEquals(1, aggregator.snapshot(now).channel("u0")!!.messages)
    }

    @Test
    fun `heartbeats expire after five minutes but messages last an hour`() {
        heartbeat("e1", "carol", at = now - 301)
        chat("e2", "alice", at = now - 3_000)

        val channel = aggregator.snapshot(now).channel("u0")!!

        assertEquals(1, channel.people)
        assertEquals(0, channel.online)
        assertEquals(1, channel.messages)
    }

    @Test
    fun `events outside their window or from the future are ignored`() {
        assertFalse(heartbeat("e1", "carol", at = now - 600))
        assertFalse(chat("e2", "alice", at = now - 4_000))
        assertFalse(chat("e3", "bob", at = now + 3_600))

        assertTrue(aggregator.snapshot(now).channels.isEmpty())
    }

    @Test
    fun `empty chat events count as presence not messages`() {
        chat("e1", "alice", content = "  ")

        val channel = aggregator.snapshot(now).channel("u0")!!

        assertEquals(0, channel.messages)
        assertEquals(1, channel.online)
    }

    @Test
    fun `invalid geohashes are rejected and valid ones normalized`() {
        assertFalse(chat("e1", "alice", geohash = "u0a!"))
        assertFalse(chat("e2", "alice", geohash = "ailo"))
        assertTrue(chat("e3", "alice", geohash = "U0BC"))

        assertEquals(listOf("u0bc"), aggregator.snapshot(now).channels.map { it.geohash })
    }

    @Test
    fun `prune drops channels whose activity has left every window`() {
        heartbeat("e1", "carol", at = now - 200)

        assertTrue(aggregator.prune(now + 200))
        assertNull(aggregator.snapshot(now + 200).channel("u0"))
    }

    @Test
    fun `wouldGrow is true only for new people or new messages`() {
        assertTrue(aggregator.wouldGrow("u0", "carol", isChat = false, eventId = "e1"))
        heartbeat("e1", "carol")

        assertFalse(aggregator.wouldGrow("u0", "carol", isChat = false, eventId = "e2"))
        assertTrue(aggregator.wouldGrow("u0", "carol", isChat = true, eventId = "e3"))
        assertTrue(aggregator.wouldGrow("u1", "carol", isChat = false, eventId = "e4"))
    }

    @Test
    fun `channels are ordered busiest first`() {
        heartbeat("e1", "a", geohash = "9q")
        heartbeat("e2", "a", geohash = "u0")
        heartbeat("e3", "b", geohash = "u0")

        assertEquals(listOf("u0", "9q"), aggregator.snapshot(now).channels.map { it.geohash })
    }

    @Test
    fun `nested heartbeat levels are not double counted online`() {
        // One person heartbeats region, province and city channels under three identities.
        heartbeat("e1", "id-region", geohash = "u0")
        heartbeat("e2", "id-province", geohash = "u0bc")
        heartbeat("e3", "id-city", geohash = "u0bcd")
        heartbeat("e4", "other-city", geohash = "u0bce")

        assertEquals(2, aggregator.snapshot(now).totalOnline)
    }

    @Test
    fun `distinct people estimate takes the busiest precision level`() {
        fun channel(geohash: String, people: Int) =
            GeohashChannelActivity(geohash, 0.0, 0.0, people, 0, 0, people, now)

        val estimate = estimateDistinctPeople(
            listOf(channel("u0", 5), channel("u0bc", 3), channel("u0bd", 4), channel("u0bcd", 6))
        )

        assertEquals(7, estimate)
        assertEquals(0, estimateDistinctPeople(emptyList()))
    }
}
