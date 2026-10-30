package com.bitchat.android.spam

import com.bitchat.android.model.BitchatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `character budget allows twelve hundred characters per minute`() {
        assertEquals(SpamVerdict.ALLOWED, send(prose(600)))
        assertEquals(SpamVerdict.ALLOWED, send(prose(600)))
        assertEquals(SpamVerdict.CHARACTER_RATE, send(prose(20)))
        advanceSeconds(2)
        assertEquals(SpamVerdict.ALLOWED, send(prose(20)))
    }

    @Test
    fun `message longer than the character budget is hidden`() {
        assertEquals(SpamVerdict.CHARACTER_RATE, send(prose(1201)))
    }

    @Test
    fun `characters are counted as code points`() {
        val emoji = "😀"
        val sentence = (0 until 1200).joinToString("") { if (it % 3 == 0) emoji else "${'a' + random.nextInt(26)}" }
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

    @Test
    fun `debug bypass shows spam and records why it would be hidden`() {
        filter.debugBypass = true
        val messages = (1..7).map {
            BitchatMessage(id = "b$it", sender = "flooder", content = "hi $it", timestamp = java.util.Date(now))
        }

        val hidden = messages.map { filter.shouldHide(it, "flooder") }

        assertTrue(hidden.none { it })
        assertEquals(mapOf("b7" to SpamVerdict.MESSAGE_RATE), filter.flagged.value)
    }

    @Test
    fun `spam is hidden without the debug bypass`() {
        val messages = (1..7).map {
            BitchatMessage(id = "h$it", sender = "flooder", content = "hi $it", timestamp = java.util.Date(now))
        }

        assertEquals(listOf(false, false, false, false, false, false, true), messages.map { filter.shouldHide(it, "flooder") })
        assertTrue(filter.flagged.value.isEmpty())
    }

    @Test
    fun `changing limits starts every sender with a full budget`() {
        repeat(6) { send() }
        assertEquals(SpamVerdict.MESSAGE_RATE, send())

        filter.config = SpamTuning.DEFAULT.copy(messageBurst = 10).toConfig()

        repeat(10) { assertEquals(SpamVerdict.ALLOWED, send()) }
        assertEquals(SpamVerdict.MESSAGE_RATE, send())
    }

    @Test
    fun `default tuning matches the code defaults`() {
        assertEquals(SpamFilterConfig(), SpamTuning.DEFAULT.toConfig())
        assertFalse(SpamTuning.DEFAULT.copy(linksPerMinute = 5).toConfig() == SpamFilterConfig())
    }

    @Test
    fun `same text from many throwaway identities is hidden after three copies`() {
        val text = "Free sats for everyone at freesats dot example"
        val verdicts = (1..6).map { send(text, sender = "npub$it") }

        assertEquals(
            listOf(SpamVerdict.ALLOWED, SpamVerdict.ALLOWED, SpamVerdict.ALLOWED) +
                List(3) { SpamVerdict.REPEATED_ACROSS_USERS },
            verdicts
        )
    }

    @Test
    fun `near copies with small changes from different identities are hidden`() {
        // Each variant is one small edit (a suffix, an insertion, a substitution) off the first.
        val variants = listOf(
            "Free sats for everyone, claim yours now",
            "free sats for everyone, claim yours now 1",
            "Free sats for everyone!! claim yours now",
            "free sats 4 everyone, claim yours now",
            "free  sats for everyone, claim yours now 55"
        )
        val verdicts = variants.mapIndexed { i, text -> send(text, sender = "npub$i") }

        assertEquals(List(4) { SpamVerdict.ALLOWED } + SpamVerdict.REPEATED_ACROSS_USERS, verdicts)
    }

    @Test
    fun `short common replies from many users are not compared`() {
        repeat(8) { assertEquals(SpamVerdict.ALLOWED, send("good morning!", sender = "user$it")) }
    }

    @Test
    fun `different messages on the same topic from many users are allowed`() {
        val messages = listOf(
            "is the bakery on the corner still open?",
            "the bakery closes at eight I think",
            "bakery was still open when I walked by",
            "anyone know if the bakery has bread left?",
            "the corner bakery is open until eight"
        )
        messages.forEachIndexed { i, text -> assertEquals(SpamVerdict.ALLOWED, send(text, sender = "user$i")) }
    }

    @Test
    fun `copies from other users outside the window do not count`() {
        val text = "anyone up for chess at the tables later?"
        repeat(3) {
            send(text, sender = "user$it")
            advanceSeconds(4 * 60)
        }
        // Only the copy from 4 minutes ago and the one from 8 minutes ago are in the window.
        assertEquals(SpamVerdict.ALLOWED, send(text, sender = "late"))
    }

    @Test
    fun `a sender's own copies do not count toward the cross-user limit`() {
        val text = "anyone near the north gate tonight?"
        send(text, sender = "alice")
        advanceSeconds(60)
        send(text, sender = "alice")
        assertEquals(SpamVerdict.ALLOWED, send(text, sender = "bob"))
        assertEquals(SpamVerdict.REPEATED_ACROSS_USERS, send(text, sender = "carol"))
    }

    @Test
    fun `best alignment reports where the texts line up`() {
        val a = RepetitionDetector.normalize("xx hello world", 1000)
        val b = RepetitionDetector.normalize("hello world", 1000)
        val alignment = RepetitionDetector.align(a, b)
        assertEquals(3, alignment.peaks.first().shift)
        assertEquals(11, alignment.matches)
    }

    @Test
    fun `an inserted character does not break the match`() {
        val a = RepetitionDetector.normalize("free sats for everyone, claim yours now", 1000)
        val b = RepetitionDetector.normalize("free sats for everyone!!, claim yours now", 1000)
        assertTrue(RepetitionDetector.align(a, b, maxPeaks = 1).score < 0.8)
        assertTrue(RepetitionDetector.align(a, b).score > 0.9)
    }

    @Test
    fun `unrelated sentences stay far below the near-copy threshold`() {
        val a = RepetitionDetector.normalize("the bakery closes at eight I think", 1000)
        val b = RepetitionDetector.normalize("the corner bakery is open until eight", 1000)
        assertTrue(RepetitionDetector.align(a, b).score < 0.6)
    }

    private val disguiseBase = "Free sats for everyone, claim yours now"

    /** The same text a reader sees, written so that a plain comparison would miss it. */
    private val disguises = mapOf(
        "zero-width spaces" to disguiseBase.map { "$it​" }.joinToString(""),
        "mixed invisible characters" to disguiseBase.mapIndexed { i, c ->
            "$c" + listOf("‌", "‍", "⁠", "­", "‎")[i % 5]
        }.joinToString(""),
        "an accent on every letter" to disguiseBase.map { if (it.isLetter()) "$it́" else "$it" }.joinToString(""),
        "math bold letters" to disguiseBase.map { c ->
            when (c) {
                in 'a'..'z' -> String(Character.toChars(0x1D41A + (c - 'a')))
                in 'A'..'Z' -> String(Character.toChars(0x1D400 + (c - 'A')))
                else -> "$c"
            }
        }.joinToString(""),
        "fullwidth letters" to disguiseBase.map { c -> if (c in '!'..'~') (c.code + 0xFEE0).toChar() else c }.joinToString(""),
        "non-breaking spaces" to disguiseBase.replace(' ', ' ')
    )

    @Test
    fun `normalization folds disguises to the text a reader sees`() {
        val plain = RepetitionDetector.normalize(disguiseBase, 1000).toList()
        disguises.forEach { (label, text) ->
            assertEquals(label, plain, RepetitionDetector.normalize(text, 1000).toList())
        }
    }

    @Test
    fun `disguised copies from other users are still caught`() {
        disguises.forEach { (label, text) ->
            val fresh = SpamFilter(clock = { now })
            repeat(3) { fresh.check("npub$it", "$label-$it", disguiseBase, now) }
            assertEquals(label, SpamVerdict.REPEATED_ACROSS_USERS, fresh.check("npub9", "$label-x", text, now))
        }
    }

    @Test
    fun `disguised copies from the same user are still caught`() {
        disguises.forEach { (label, text) ->
            val fresh = SpamFilter(clock = { now })
            fresh.check("alice", "$label-1", disguiseBase, now)
            fresh.check("alice", "$label-2", disguiseBase, now)
            assertEquals(label, SpamVerdict.REPEATED_MESSAGE, fresh.check("alice", "$label-3", text, now))
        }
    }

    @Test
    fun `invisible characters cannot break up a repeated character run`() {
        assertEquals(SpamVerdict.REPEATED_CHARACTERS, send("A​".repeat(40)))
    }

    @Test
    fun `ordinary text with accents and other scripts is still allowed`() {
        listOf(
            "Tiếng Việt có dấu, chúng tôi đang ở quán cà phê gần chợ",
            "क्या कोई पास में चाय की दुकान जानता है? मैं स्टेशन के पास हूँ",
            "Café au lait, crème brûlée, s'il vous plaît",
            "家族と一緒に駅の近くのカフェにいます、誰か来ますか"
        ).forEachIndexed { i, text -> assertEquals(text, SpamVerdict.ALLOWED, send(text, sender = "user$i")) }
    }

    @Test
    fun `different sentences in another script are not mistaken for copies`() {
        assertEquals(SpamVerdict.ALLOWED, send("क्या कोई पास में चाय की दुकान जानता है?", sender = "a"))
        assertEquals(SpamVerdict.ALLOWED, send("मैं स्टेशन के पास हूँ, बारिश हो रही है", sender = "b"))
        assertEquals(SpamVerdict.ALLOWED, send("आज बाज़ार कितने बजे बंद होगा?", sender = "c"))
        assertEquals(SpamVerdict.ALLOWED, send("क्या किसी के पास छाता है जो मैं ले सकूँ?", sender = "d"))
    }
}
