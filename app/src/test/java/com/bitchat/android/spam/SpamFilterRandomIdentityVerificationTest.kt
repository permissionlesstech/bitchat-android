package com.bitchat.android.spam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Local verification of the public flood threat model, using synthetic inputs only. */
class SpamFilterRandomIdentityVerificationTest {
    private val seed = System.getenv("TEST_SEED")?.toIntOrNull() ?: 999
    private val timestampMs = 1_000_000_000L

    private fun baseline() = SpamFilter(
        config = SpamFilterConfig(englishGibberishEnabled = false),
        clock = { timestampMs }
    )

    private fun text(random: Random, length: Int): String = buildString {
        repeat(length) { index ->
            append(if (index % 8 == 7) ' ' else 'a' + random.nextInt(26))
        }
    }

    private fun identity(random: Random, hexLength: Int): String = buildString {
        repeat(hexLength) { append("0123456789abcdef"[random.nextInt(16)]) }
    }

    @Test
    fun `English model rejects the earlier random identity flood while the baseline admits it`() {
        listOf("mesh", "nostr").forEach { transport ->
            val random = Random(seed)
            val unprotected = baseline()
            val filter = SpamFilter(clock = { timestampMs })
            assertTrue(filter.enabled)
            assertTrue(!filter.debugBypass)
            val identities = HashSet<String>()
            val contents = HashSet<String>()
            var baselineAllowed = 0
            val verdicts = (0 until 10_000).map { index ->
                val sender = identity(random, if (transport == "mesh") 16 else 64)
                val content = text(random, random.nextInt(32, 161))
                assertTrue("TEST_SEED=$seed duplicate identity", identities.add(sender))
                assertTrue("TEST_SEED=$seed duplicate content", contents.add(content))
                val senderKey = if (transport == "nostr") SpamFilter.nostrSenderKey(sender) else "mesh:$sender"
                if (unprotected.check(senderKey, "$transport-$index", content, timestampMs) == SpamVerdict.ALLOWED) {
                    baselineAllowed++
                }
                filter.check(senderKey, "$transport-$index", content, timestampMs)
            }
            val counts = verdicts.groupingBy { it }.eachCount()
            println("TEST_SEED=$seed transport=$transport messages=10000 baselineAllowed=$baselineAllowed verdicts=$counts")
            assertEquals("TEST_SEED=$seed $transport baseline", 10_000, baselineAllowed)
            assertTrue("TEST_SEED=$seed $transport $counts", counts.getOrDefault(SpamVerdict.ENGLISH_GIBBERISH, 0) >= 9_500)
        }
    }

    @Test
    fun `same identity random text control exhausts the message bucket`() {
        val random = Random(seed)
        val filter = baseline()
        val counts = (0 until 1_000).map { index ->
            filter.check("mesh:synthetic-sender", "same-$index", text(random, 80), timestampMs)
        }.groupingBy { it }.eachCount()
        println("TEST_SEED=$seed same-identity messages=1000 verdicts=$counts")
        assertEquals(mapOf(SpamVerdict.ALLOWED to 6, SpamVerdict.MESSAGE_RATE to 994), counts)
    }

    @Test
    fun `fresh identity copied text control triggers cross user filtering`() {
        val random = Random(seed)
        val content = text(random, 80)
        val filter = baseline()
        val counts = (0 until 1_000).map { index ->
            filter.check(SpamFilter.nostrSenderKey(identity(random, 64)), "copy-$index", content, timestampMs)
        }.groupingBy { it }.eachCount()
        println("TEST_SEED=$seed copied-text messages=1000 verdicts=$counts")
        assertEquals(mapOf(SpamVerdict.ALLOWED to 3, SpamVerdict.REPEATED_ACROSS_USERS to 997), counts)
    }

    @Test
    fun `oversized random text control is hidden even for fresh identities`() {
        val random = Random(seed)
        val filter = baseline()
        val counts = (0 until 100).map { index ->
            filter.check(SpamFilter.nostrSenderKey(identity(random, 64)), "oversize-$index", text(random, 1201), timestampMs)
        }.groupingBy { it }.eachCount()
        println("TEST_SEED=$seed oversized messages=100 verdicts=$counts")
        assertEquals(mapOf(SpamVerdict.CHARACTER_RATE to 100), counts)
    }

    @Test
    fun `independent random seeds exercise unseen gibberish`() {
        listOf(seed + 1, seed + 2, seed + 3).forEach { evaluationSeed ->
            val random = Random(evaluationSeed)
            val filter = SpamFilter(clock = { timestampMs })
            val counts = (0 until 2_000).map { index ->
                filter.check("mesh:" + identity(random, 16), "unseen-$index", text(random, random.nextInt(32, 161)), timestampMs)
            }.groupingBy { it }.eachCount()
            println("TEST_SEED=$evaluationSeed unseen-messages=2000 verdicts=$counts")
            assertTrue("TEST_SEED=$evaluationSeed $counts", counts.getOrDefault(SpamVerdict.ENGLISH_GIBBERISH, 0) >= 1_900)
        }
    }

    @Test
    fun `English model does not newly hide legitimate controls from fresh identities`() {
        val samples = EnglishGibberishSamples.english + EnglishGibberishSamples.multilingual + EnglishGibberishSamples.technical
        val unprotected = baseline()
        val filter = SpamFilter(clock = { timestampMs })
        var baselineHidden = 0
        val changed = samples.mapIndexedNotNull { index, content ->
            val before = unprotected.check("mesh:synthetic-$index", "control-$index", content, timestampMs)
            val after = filter.check("mesh:synthetic-$index", "control-$index", content, timestampMs)
            if (before.isSpam) baselineHidden++
            if (before != after) "$before -> $after" else null
        }
        println("Legitimate admission controls=${samples.size} baselineHidden=$baselineHidden changed=${changed.size}")
        assertTrue(changed.toString(), changed.isEmpty())
    }
}
