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


    /** A ZWJ sequence is one grapheme, so it is kept or dropped as a whole. */



}
