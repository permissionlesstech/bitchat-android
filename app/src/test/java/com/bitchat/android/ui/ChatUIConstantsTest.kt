package com.bitchat.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatUIConstantsTest {

    @Test
    fun `a name inside the limit is returned unchanged`() {
        assertEquals("alice", truncateNickname("alice", maxLen = 15))
        assertEquals("exactlyfifteen!", truncateNickname("exactlyfifteen!", maxLen = 15))
    }

    @Test
    fun `an ascii name is still cut at the limit`() {
        assertEquals("hello world thi", truncateNickname("hello world this is long", maxLen = 15))
    }

    /**
     * The limit counts UTF-16 code units, so the 15th unit of this name is the
     * high surrogate of the emoji. Cutting there leaves a lone surrogate that
     * renders as a tofu box.
     */
    @Test
    fun `a surrogate pair is never split`() {
        val truncated = truncateNickname("abcdefghijklmn😀", maxLen = 15)

        assertEquals("abcdefghijklmn", truncated)
        assertTrue(
            "truncated name must not end in a lone surrogate",
            truncated.none { Character.isSurrogate(it) }
        )
    }

    @Test
    fun `an emoji that fits is kept whole`() {
        assertEquals("abcdefghijkl😀m", truncateNickname("abcdefghijkl😀mno", maxLen = 15))
    }

    /** A ZWJ sequence is one grapheme, so it is kept or dropped as a whole. */
    @Test
    fun `a zwj sequence is not split into its parts`() {
        val family = "👨‍👩‍👧"
        val truncated = truncateNickname(family + " family chat", maxLen = 15)

        assertEquals(family + " family", truncated)
        assertTrue("the family must survive whole", truncated.startsWith(family))
    }

    @Test
    fun `a name made only of one long grapheme truncates to empty rather than half a character`() {
        val family = "👨‍👩‍👧‍👦"
        val truncated = truncateNickname(family, maxLen = 5)

        assertEquals("", truncated)
    }

    @Test
    fun `every truncation stays within the limit and holds no partial character`() {
        val samples = listOf(
            "abcdefghijklmn😀",
            "🇮🇳 india mesh",
            "👍🏽 thumbs up all round",
            "plain long nickname here"
        )
        for (name in samples) {
            for (limit in 1..20) {
                val truncated = truncateNickname(name, maxLen = limit)
                assertTrue("$name at $limit exceeded the limit", truncated.length <= limit)
                assertTrue("$name at $limit is not a prefix of the name", name.startsWith(truncated))
                assertTrue(
                    "$name at $limit ended in a lone surrogate",
                    truncated.isEmpty() || !Character.isHighSurrogate(truncated.last())
                )
            }
        }
    }
}
