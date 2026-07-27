package com.bitchat.android.cashu

import com.bitchat.android.ui.MessageSpecialParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Detection parity tests for [MessageSpecialParser.findCashuTokens]:
 * bare + URI forms, dedup, max count, and word-boundary behavior.
 */
@RunWith(RobolectricTestRunner::class)
class CashuTokenDetectionTest {

    private val tokenA = "cashuA" + "eyJ0b2tlbiI6W119" + "abc"
    private val tokenB = "cashuB" + "o2FhAWFz" + "xyz"

    @Test
    fun `detects bare tokens in message text`() {
        val matches = MessageSpecialParser.findCashuTokens("here you go $tokenA thanks")
        assertEquals(1, matches.size)
        assertEquals(tokenA, matches[0].token)
    }

    @Test
    fun `detects token embedded in cashu uri`() {
        for (uri in listOf("cashu:$tokenA", "cashu://$tokenA")) {
            val matches = MessageSpecialParser.findCashuTokens(uri)
            assertEquals(1, matches.size)
            assertEquals(tokenA, matches[0].token)
        }
    }

    @Test
    fun `detects both v3 and v4 tokens`() {
        val matches = MessageSpecialParser.findCashuTokens("$tokenA and $tokenB")
        assertEquals(listOf(tokenA, tokenB), matches.map { it.token })
    }

    @Test
    fun `deduplicates repeated tokens`() {
        val matches = MessageSpecialParser.findCashuTokens("$tokenA $tokenA $tokenA")
        assertEquals(1, matches.size)
    }

    @Test
    fun `caps at max tokens`() {
        val text = (1..6).joinToString(" ") { "cashuA" + "abcdef$it" + "x".repeat(6) }
        val matches = MessageSpecialParser.findCashuTokens(text, max = 3)
        assertEquals(3, matches.size)
    }

    @Test
    fun `trims trailing prose punctuation from token`() {
        for (punct in listOf(".", "!", "?", ",", ";", ":")) {
            val matches = MessageSpecialParser.findCashuTokens("redeem $tokenA$punct")
            assertEquals(1, matches.size)
            assertEquals(tokenA, matches[0].token)
        }
    }

    @Test
    fun `keeps internal multipart dots`() {
        val multipart = "cashuA" + "abcdef.ghijkl"
        val matches = MessageSpecialParser.findCashuTokens(multipart)
        assertEquals(listOf(multipart), matches.map { it.token })
    }

    @Test
    fun `does not match words containing cashu prefix`() {
        assertTrue(MessageSpecialParser.findCashuTokens("xcashuAabcdefgh").isEmpty())
        assertTrue(MessageSpecialParser.findCashuTokens("cashuCabcdefgh").isEmpty())
        assertTrue(MessageSpecialParser.findCashuTokens("cashuA").isEmpty())
        assertTrue(MessageSpecialParser.findCashuTokens("no tokens here").isEmpty())
    }
}
