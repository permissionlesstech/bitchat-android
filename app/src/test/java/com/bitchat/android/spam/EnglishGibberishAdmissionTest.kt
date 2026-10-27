package com.bitchat.android.spam

import com.bitchat.android.model.BitchatMessage
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EnglishGibberishAdmissionTest {
    private val timestampMs = 1_000_000_000L
    private val junk = "qxjvtpk zmxqvfb jkvxzpq vxjqzmt qzpvxjk"

    @Test
    fun `English gibberish is hidden and its verdict is cached by message ID`() {
        val filter = SpamFilter(clock = { timestampMs })
        assertEquals(SpamVerdict.ENGLISH_GIBBERISH, filter.check("mesh:synthetic-a", "junk", junk, timestampMs))
        assertEquals(SpamVerdict.ENGLISH_GIBBERISH, filter.check("mesh:synthetic-a", "junk", junk, timestampMs))
    }

    @Test
    fun `hidden gibberish does not spend a senders message allowance`() {
        val filter = SpamFilter(clock = { timestampMs })
        repeat(20) { index ->
            assertEquals(SpamVerdict.ENGLISH_GIBBERISH, filter.check("mesh:synthetic-a", "junk-$index", junk, timestampMs))
        }
        listOf("ok", "thanks", "yes", "no", "fine", "hello").forEachIndexed { index, content ->
            assertEquals(SpamVerdict.ALLOWED, filter.check("mesh:synthetic-a", "ok-$index", content, timestampMs))
        }
        assertEquals(SpamVerdict.MESSAGE_RATE, filter.check("mesh:synthetic-a", "over-limit", "thanks", timestampMs))
    }

    @Test
    fun `the English rule can be disabled independently and changing config clears cached decisions`() {
        val filter = SpamFilter(clock = { timestampMs })
        assertEquals(SpamVerdict.ENGLISH_GIBBERISH, filter.check("mesh:synthetic-a", "same", junk, timestampMs))
        filter.config = filter.config.copy(englishGibberishEnabled = false)
        assertEquals(SpamVerdict.ALLOWED, filter.check("mesh:synthetic-a", "same", junk, timestampMs))
    }

    @Test
    fun `the master filter toggle still allows gibberish`() {
        val filter = SpamFilter(clock = { timestampMs }).apply { enabled = false }
        assertEquals(SpamVerdict.ALLOWED, filter.check("mesh:synthetic-a", "junk", junk, timestampMs))
    }

    @Test
    fun `media descriptions are not scored as English text`() {
        val filter = SpamFilter(clock = { timestampMs })
        assertEquals(SpamVerdict.ALLOWED, filter.check("mesh:synthetic-a", "media", junk, timestampMs, isText = false))
    }

    @Test
    fun `the existing debug bypass displays gibberish with the correct reason`() {
        val filter = SpamFilter(clock = { timestampMs }).apply { debugBypass = true }
        val message = BitchatMessage(id = "synthetic-junk", sender = "synthetic", content = junk, timestamp = Date(timestampMs))
        assertFalse(filter.shouldHide(message, "mesh:synthetic-a"))
        assertEquals(mapOf(message.id to SpamVerdict.ENGLISH_GIBBERISH), filter.flagged.value)
        filter.reset()
        assertEquals(emptyMap<String, SpamVerdict>(), filter.flagged.value)
    }
}
