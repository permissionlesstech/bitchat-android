package com.bitchat.android.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeohashActivityAggregatorTest {

    private val now = 1_800_000_000_000L
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
        heartbeat("e1", "carol", at = now - 301_000)
        chat("e2", "alice", at = now - 3_000_000)

        val channel = aggregator.snapshot(now).channel("u0")!!

        assertEquals(1, channel.people)
        assertEquals(0, channel.online)
        assertEquals(1, channel.messages)
    }

    @Test
    fun `events outside their window are ignored`() {
        assertFalse(heartbeat("e1", "carol", at = now - 600_000))
        assertFalse(chat("e2", "alice", at = now - 4_000_000))

        assertTrue(aggregator.snapshot(now).channels.isEmpty())
    }

    @Test
    fun `future timestamps are clamped to now`() {
        heartbeat("e1", "carol", at = now + 3_600_000)

        assertEquals(mapOf("carol" to now), aggregator.activePeople("u0", now))
        assertTrue(aggregator.activePeople("u0", now + 300_001).isEmpty())
    }

    @Test
    fun `a chatter stays counted for the hour after their heartbeats stop`() {
        chat("e1", "alice", at = now)

        assertEquals(setOf("alice"), aggregator.activePeople("u0", now + 40 * 60_000).keys)
        assertTrue(aggregator.activePeople("u0", now + 61 * 60_000).isEmpty())
    }

    @Test
    fun `online count lapses with time even when prune keeps the chatter`() {
        chat("e1", "alice", at = now)
        val later = now + 6 * 60_000

        // Nothing is removable: alice's message keeps her listed for the hour...
        assertFalse(aggregator.prune(later))
        val channel = aggregator.snapshot(later).channel("u0")!!

        // ...but she is no longer online, so a snapshot must be recomputed on a timer.
        assertEquals(1, channel.people)
        assertEquals(0, channel.online)
        assertEquals(0, aggregator.snapshot(later).totalOnline)
    }

    @Test
    fun `a busy channel keeps earlier writers for the hour beyond the message cap`() {
        chat("first", "alice", at = now - 600_000)
        repeat(600) { chat("spam$it", "bob", at = now - 60_000) }
        aggregator.prune(now)

        val channel = aggregator.snapshot(now).channel("u0")!!

        assertEquals(2, channel.people)
        assertEquals(2, channel.chatters)
        assertTrue("alice" in aggregator.activePeople("u0", now))
    }

    @Test
    fun `a newest-first backfill past the cap keeps the newest messages`() {
        // Relays return stored events newest first: message 0 is a minute old, message 599 about
        // 51 minutes old. Only the oldest should be dropped at the cap.
        repeat(600) { chat("m$it", "bob", at = now - 60_000 - it * 5_000L) }

        // Twenty minutes on, messages 0..468 are still inside the hour. Dropping by arrival order
        // would have kept 100..599 instead and counted only 369.
        val later = now + 1_200_000
        val messages = aggregator.snapshot(later).channel("u0")?.messages

        assertEquals(469, messages)
    }

    @Test
    fun `excluded identities drop out of every count`() {
        chat("e1", "alice")
        heartbeat("e2", "blocked")

        val channel = aggregator.snapshot(now) { it == "blocked" }.channel("u0")!!

        assertEquals(1, channel.people)
        assertEquals(1, channel.online)
        assertEquals(1, channel.messages)
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
        heartbeat("e1", "carol", at = now - 200_000)

        assertTrue(aggregator.prune(now + 200_000))
        assertNull(aggregator.snapshot(now + 200_000).channel("u0"))
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
