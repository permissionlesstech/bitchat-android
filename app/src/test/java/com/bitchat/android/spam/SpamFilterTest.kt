package com.bitchat.android.spam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SpamFilterTest {

    private var now = 1_000_000_000L
    private var nextId = 0
    private val random = Random(7)
    private val filter = SpamFilter(clock = { now })

    private fun send(
        content: String = prose(),
        sender: String = "alice",
        timestampMs: Long = now,
        id: String = "m${nextId++}"
    ) = filter.check(sender, id, content, timestampMs)

    /** Unrelated synthetic words, so only the rule under test can fire. */
    private fun prose(length: Int = 40): String = buildString {
        while (this.length < length) {
            if (isNotEmpty()) append(' ')
            repeat(random.nextInt(2, 8)) { append('a' + random.nextInt(26)) }
        }
    }.take(length)

    private fun advanceSeconds(seconds: Int) {
        now += seconds * 1000L
    }

    @Test
    fun `sixth message in a burst is shown and seventh is hidden`() {
        repeat(6) { assertEquals(SpamVerdict.ALLOWED, send()) }
        assertEquals(SpamVerdict.MESSAGE_RATE, send())
    }

    @Test
    fun `message budget refills one token every ten seconds`() {
        repeat(6) { send() }
        advanceSeconds(9)
        assertEquals(SpamVerdict.MESSAGE_RATE, send())
        advanceSeconds(1)
        assertEquals(SpamVerdict.ALLOWED, send())
        assertEquals(SpamVerdict.MESSAGE_RATE, send())
    }

    @Test
    fun `hidden messages do not drain the budget of a flooding sender`() {
        var shown = 0
        repeat(120) {
            if (send() == SpamVerdict.ALLOWED) shown++
            advanceSeconds(1)
        }
        // A burst of 6, then one every 10 seconds for the remaining two minutes.
        assertEquals(6 + 11, shown)
    }

    @Test
    fun `character budget allows six hundred characters per minute`() {
        assertEquals(SpamVerdict.ALLOWED, send(prose(300)))
        assertEquals(SpamVerdict.ALLOWED, send(prose(300)))
        assertEquals(SpamVerdict.CHARACTER_RATE, send(prose(20)))
        advanceSeconds(2)
        assertEquals(SpamVerdict.ALLOWED, send(prose(20)))
    }

    @Test
    fun `message longer than the character budget is hidden`() {
        assertEquals(SpamVerdict.CHARACTER_RATE, send(prose(601)))
    }

    @Test
    fun `characters are counted as code points`() {
        val emoji = "😀"
        val sentence = (0 until 600).joinToString("") { if (it % 3 == 0) emoji else "${'a' + random.nextInt(26)}" }
        assertEquals(SpamVerdict.ALLOWED, send(sentence))
    }

    @Test
    fun `third link within a minute is hidden`() {
        assertEquals(SpamVerdict.ALLOWED, send("see https://example.org/a for details"))
        assertEquals(SpamVerdict.ALLOWED, send("mirror at www.example.net too"))
        assertEquals(SpamVerdict.LINK_RATE, send("and example.com/promo as well"))
        assertEquals(SpamVerdict.ALLOWED, send("no link in this one at all"))
        advanceSeconds(30)
        assertEquals(SpamVerdict.ALLOWED, send("ok one more https://example.org/b"))
    }

    @Test
    fun `message with three links is hidden even for a fresh sender`() {
        assertEquals(
            SpamVerdict.LINK_RATE,
            send("https://alpha.example/one and bravo.example plus www.charlie.example/three")
        )
    }

    @Test
    fun `link counter ignores email addresses and abbreviations`() {
        assertEquals(0, LinkCounter.count("mail me at someone@example.com e.g. today"))
        assertEquals(0, LinkCounter.count("pi is 3.14, version v1.2.3"))
        assertEquals(3, LinkCounter.count("https://a.example/x, www.b.example and c.example."))
    }

    @Test
    fun `third copy of a message within ten minutes is hidden`() {
        val text = "anyone near the north gate tonight?"
        assertEquals(SpamVerdict.ALLOWED, send(text))
        advanceSeconds(60)
        assertEquals(SpamVerdict.ALLOWED, send(text))
        advanceSeconds(60)
        assertEquals(SpamVerdict.REPEATED_MESSAGE, send(text))
    }

    @Test
    fun `near copies with a changing suffix count as repeats`() {
        assertEquals(SpamVerdict.ALLOWED, send("FREE SATS at the link below 101"))
        advanceSeconds(20)
        assertEquals(SpamVerdict.ALLOWED, send("free sats at the link below 202"))
        advanceSeconds(20)
        assertEquals(SpamVerdict.REPEATED_MESSAGE, send("free  sats at the link below 303"))
    }

    @Test
    fun `repeats spread beyond the history window are allowed`() {
        val text = "good morning everyone, coffee anyone?"
        send(text)
        advanceSeconds(11 * 60)
        send(text)
        advanceSeconds(11 * 60)
        assertEquals(SpamVerdict.ALLOWED, send(text))
    }

    @Test
    fun `short replies may repeat freely`() {
        repeat(5) {
            assertEquals(SpamVerdict.ALLOWED, send("lol yes"))
            advanceSeconds(10)
        }
    }

    @Test
    fun `message repeating a character is hidden`() {
        assertEquals(SpamVerdict.REPEATED_CHARACTERS, send("A".repeat(40)))
        assertEquals(SpamVerdict.REPEATED_CHARACTERS, send("hey " + "!".repeat(60)))
    }

    @Test
    fun `message repeating a phrase is hidden`() {
        assertEquals(SpamVerdict.REPEATED_CHARACTERS, send("buy now ".repeat(6)))
    }

    @Test
    fun `ordinary long sentence is not mistaken for repetition`() {
        val text = "The market by the old bridge opens at nine, bring cash because the card reader is flaky."
        assertTrue(RepetitionDetector.selfSimilarity(RepetitionDetector.normalize(text, 1000)) < 0.3)
        assertEquals(SpamVerdict.ALLOWED, send(text))
    }

    @Test
    fun `senders have independent budgets`() {
        repeat(6) { send(sender = "alice") }
        assertEquals(SpamVerdict.MESSAGE_RATE, send(sender = "alice"))
        assertEquals(SpamVerdict.ALLOWED, send(sender = "bob"))
    }

    @Test
    fun `same message ID arriving twice is judged once`() {
        repeat(5) { send() }
        assertEquals(SpamVerdict.ALLOWED, send(id = "dup"))
        assertEquals(SpamVerdict.ALLOWED, send(id = "dup"))
        assertEquals(SpamVerdict.MESSAGE_RATE, send())
    }

    @Test
    fun `backfilled history delivered newest first is shown`() {
        // Ten messages, one per minute over the last ten minutes, arriving in one burst.
        val verdicts = (0 until 10).map { minutesAgo ->
            send(timestampMs = now - minutesAgo * 60_000L)
        }
        assertTrue(verdicts.all { it == SpamVerdict.ALLOWED })
    }

    @Test
    fun `backfilled burst is still rate limited on its own timestamps`() {
        val start = now - 5 * 60_000L
        val verdicts = (0 until 10).map { send(timestampMs = start + it * 1000L) }
        assertEquals(6, verdicts.count { it == SpamVerdict.ALLOWED })
    }

    @Test
    fun `backfill cannot be squeezed in between already shown messages`() {
        repeat(6) { send(timestampMs = now - 60_000L) }
        // A message claiming to be from just before the burst would overdraw the bucket.
        assertEquals(SpamVerdict.MESSAGE_RATE, send(timestampMs = now - 61_000L))
    }

    @Test
    fun `timestamps older than the backdate window are clamped`() {
        // Spreading fake timestamps over days must not buy extra budget.
        val verdicts = (0 until 10).map { send(timestampMs = now - (10 - it) * 86_400_000L) }
        assertEquals(6, verdicts.count { it == SpamVerdict.ALLOWED })
    }

    @Test
    fun `future timestamps are clamped to now`() {
        val verdicts = (0 until 10).map { send(timestampMs = now + it * 60_000L) }
        assertEquals(6, verdicts.count { it == SpamVerdict.ALLOWED })
    }

    @Test
    fun `disabled filter allows everything`() {
        filter.enabled = false
        repeat(20) { assertEquals(SpamVerdict.ALLOWED, send("A".repeat(40))) }
    }

    @Test
    fun `limits are configurable`() {
        val strict = SpamFilter(
            config = SpamFilterConfig(messageBucket = TokenBucketConfig(capacity = 2.0, refillPerSecond = 1.0)),
            clock = { now }
        )
        assertEquals(SpamVerdict.ALLOWED, strict.check("a", "1", prose(), now))
        assertEquals(SpamVerdict.ALLOWED, strict.check("a", "2", prose(), now))
        assertEquals(SpamVerdict.MESSAGE_RATE, strict.check("a", "3", prose(), now))
        advanceSeconds(1)
        assertEquals(SpamVerdict.ALLOWED, strict.check("a", "4", prose(), now))
    }
}
