package com.bitchat.android.spam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class NewSenderPoolTest {
    private var now = 1_000_000_000L
    private val filter = SpamFilter(clock = { now })
    private val random = Random(42)

    private fun text(): String = (0 until random.nextInt(4, 12)).joinToString(" ") {
        (0 until random.nextInt(2, 8)).map { 'a' + random.nextInt(26) }.joinToString("")
    }

    private fun key(i: Int) = SpamFilter.nostrSenderKey(i.toString(16).padStart(64, '0'))

    private fun geo(sender: String, id: String, time: Long = now, channel: String = "abcde", proven: Boolean = false) =
        filter.check(sender, id, text(), time, channel = channel, proven = proven)

    @Test
    fun `fresh geohash identities share one pool per channel`() {
        val verdicts = (0 until 10_000).map { geo(key(it), "m$it") }
        assertEquals(6, verdicts.count { it == SpamVerdict.ALLOWED })
        assertEquals(9_994, verdicts.count { it == SpamVerdict.NEW_SENDER_POOL })
    }

    @Test
    fun `pool refills at six per minute`() {
        repeat(6) { geo(key(it), "a$it") }
        now += 60_000
        val verdicts = (100 until 200).map { geo(key(it), "b$it") }
        assertEquals(6, verdicts.count { it == SpamVerdict.ALLOWED })
    }

    @Test
    fun `messages without a channel are judged exactly as before`() {
        val verdicts = (0 until 1_000).map { filter.check("mesh:${key(it)}", "m$it", text(), now) }
        assertTrue(verdicts.all { it == SpamVerdict.ALLOWED })
    }

    @Test
    fun `proven senders skip the pool`() {
        repeat(6) { geo(key(it), "a$it") }
        assertEquals(SpamVerdict.NEW_SENDER_POOL, geo(key(500), "x"))
        assertEquals(SpamVerdict.ALLOWED, geo(key(501), "y", proven = true))
    }

    @Test
    fun `with a delay a sender is established that long after its first admitted message`() {
        val filter = SpamFilter(SpamFilterConfig(establishAfterMs = 2 * 60_000L), clock = { now })
        fun geo(sender: String, id: String) = filter.check(sender, id, text(), now, channel = "abcde")
        val alice = key(7)
        assertEquals(SpamVerdict.ALLOWED, geo(alice, "a1"))
        repeat(6) { geo(key(100 + it), "f$it") } // empties the pool
        now += 60_000
        repeat(6) { geo(key(200 + it), "g$it") }
        assertEquals(SpamVerdict.NEW_SENDER_POOL, geo(alice, "a2"))
        now += 60_000
        repeat(6) { geo(key(300 + it), "h$it") }
        assertEquals(SpamVerdict.ALLOWED, geo(alice, "a3"))
    }

    @Test
    fun `by default a sender is established from its first admitted message`() {
        val alice = key(7)
        assertEquals(SpamVerdict.ALLOWED, geo(alice, "a1"))
        repeat(20) { geo(key(100 + it), "f$it") }
        assertEquals(SpamVerdict.ALLOWED, geo(alice, "a2"))
    }

    @Test
    fun `a flood in one channel leaves other channels alone`() {
        repeat(1_000) { geo(key(it), "m$it", channel = "aaaaa") }
        assertEquals(SpamVerdict.ALLOWED, geo(key(5_000), "quiet", channel = "bbbbb"))
    }

    @Test
    fun `pool-refused flood cannot evict a real sender`() {
        val alice = key(1)
        val first = (0 until 7).map { filter.check(alice, "a$it", text(), now) } // mesh-style, no pool
        assertEquals(SpamVerdict.MESSAGE_RATE, first.last())
        repeat(6) { geo(key(10 + it), "p$it") }
        repeat(2_000) { geo(key(100 + it), "f$it") }
        assertEquals(SpamVerdict.MESSAGE_RATE, filter.check(alice, "a-after", text(), now))
    }

    @Test
    fun `copied text is caught by content checks without spending pool tokens`() {
        val copy = "this exact sentence is posted by every throwaway identity"
        val verdicts = (0 until 50).map { filter.check(key(it), "c$it", copy, now, channel = "abcde") }
        assertEquals(3, verdicts.count { it == SpamVerdict.ALLOWED })
        assertTrue(verdicts.drop(3).all { it == SpamVerdict.REPEATED_ACROSS_USERS })
        val fresh = (100 until 110).map { geo(key(it), "n$it") }
        assertEquals(3, fresh.count { it == SpamVerdict.ALLOWED })
    }

    @Test
    fun `reset forgets established senders and refills pools`() {
        repeat(6) { geo(key(it), "a$it") }
        filter.reset()
        assertEquals(SpamVerdict.ALLOWED, geo(key(999), "after"))
    }

    /** Geohash-only version of the random-identity flood from the test/pr999-random-identity-flood branch. */
    @Test
    fun `random text flood from fresh geohash identities is cut to the pool`() {
        val r = Random(999)
        val verdicts = (0 until 10_000).map { index ->
            val sender = (0 until 64).map { "0123456789abcdef"[r.nextInt(16)] }.joinToString("")
            val content = (0 until r.nextInt(32, 161)).map { if (it % 8 == 7) ' ' else 'a' + r.nextInt(26) }
                .joinToString("")
            filter.check(SpamFilter.nostrSenderKey(sender), "nostr-$index", content, now, channel = "abcde")
        }
        assertEquals(6, verdicts.count { !it.isSpam })
    }
}
