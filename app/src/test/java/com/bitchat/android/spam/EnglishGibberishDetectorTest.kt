package com.bitchat.android.spam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishGibberishDetectorTest {
    private val junk = "qxjvtpk zmxqvfb jkvxzpq vxjqzmt qzpvxjk"

    @Test
    fun `model version has stable literal score vectors on phone and watch`() {
        assertEquals(2.16027852438264, EnglishGibberishDetector.score(
            "the small example makes the explanation easier to understand"
        )!!, 0.000001)
        assertEquals(5.652053437734905, EnglishGibberishDetector.score(junk)!!, 0.000001)
    }

    @Test
    fun `long unrelated random words are identified without sender state`() {
        assertTrue(EnglishGibberishDetector.isGibberish(junk))
        assertTrue(EnglishGibberishDetector.score(junk)!! >= EnglishGibberishDetector.THRESHOLD)
    }

    @Test
    fun `held out English chat examples are not classified as gibberish`() {
        val rejected = EnglishGibberishSamples.english.filter(EnglishGibberishDetector::isGibberish)
        println("English model controls=${EnglishGibberishSamples.english.size} rejected=${rejected.size}")
        assertEquals(emptyList<String>(), rejected)
    }

    @Test
    fun `multilingual controls receive scores and expose English model false positives`() {
        val rejected = EnglishGibberishSamples.multilingual.filter(EnglishGibberishDetector::isGibberish)
        println("Multilingual model controls=${EnglishGibberishSamples.multilingual.size} rejected=${rejected.size}")
        EnglishGibberishSamples.multilingual.forEach { assertTrue(EnglishGibberishDetector.score(it) != null) }
        assertTrue(rejected.isNotEmpty())
    }

    @Test
    fun `short replies and technical controls no longer bypass scoring`() {
        EnglishGibberishSamples.technical.forEach { assertTrue(it, EnglishGibberishDetector.score(it) != null) }
        assertTrue(EnglishGibberishDetector.score("ok thanks") != null)
        assertTrue(EnglishGibberishDetector.score("`abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz`") != null)
        assertNull(EnglishGibberishDetector.score(""))
    }

    @Test
    fun `adding unsupported characters cannot exempt an otherwise hidden message`() {
        assertTrue(EnglishGibberishDetector.isGibberish("$junk 日本語"))
        assertTrue(EnglishGibberishDetector.isGibberish("$junk café"))
        assertTrue(EnglishGibberishDetector.isGibberish("$junk cafe\u0301"))
    }

    @Test
    fun `case and whitespace normalize while punctuation now contributes to the score`() {
        assertTrue(EnglishGibberishDetector.isGibberish(junk.uppercase()))
        assertTrue(EnglishGibberishDetector.isGibberish(junk.replace(" ", "\n\t")))
        assertEquals(EnglishGibberishDetector.score(junk), EnglishGibberishDetector.score(junk.replace(" ", "\n\t")))
        assertTrue(EnglishGibberishDetector.score("($junk)!")!! > EnglishGibberishDetector.score(junk)!!)
    }

    @Test
    fun `short text is scored without a minimum letter requirement`() {
        assertTrue(EnglishGibberishDetector.isGibberish("qxjvtpk zmxqvfb jkvxzpq"))
        assertTrue(EnglishGibberishDetector.isGibberish("qxjvtpkzmxqvfb jkvxzpqvxjqzmt"))
        assertTrue(EnglishGibberishDetector.isGibberish("qxjvtpk zmxqvfb jkvxzpq vxjqzmt"))
    }

    @Test
    fun `long unbroken synthetic gibberish is scored instead of exempted as an identifier`() {
        assertTrue(EnglishGibberishDetector.isGibberish(junk.replace(" ", "")))
        assertTrue(EnglishGibberishDetector.isGibberish(junk.replace(" ", "").repeat(30)))
    }

    @Test
    fun `long English words and descriptive identifiers are not automatically rejected`() {
        assertFalse(EnglishGibberishDetector.isGibberish("antidisestablishmentarianism"))
        assertFalse(EnglishGibberishDetector.isGibberish("pneumonoultramicroscopicsilicovolcanoconiosis"))
        assertFalse(EnglishGibberishDetector.isGibberish("thisIsASyntheticIdentifierForTesting"))
    }

    @Test
    fun `ordinary looking sentences remain a documented limitation`() {
        assertFalse(EnglishGibberishDetector.isGibberish("The invisible banana politely explained the meaning of the purple teapot."))
    }

    @Test
    fun `oversized input still scores a bounded prefix`() {
        assertTrue(EnglishGibberishDetector.isGibberish(junk.repeat(200)))
    }

    @Test
    fun `untrained characters use the strongest existing penalty without new learned weights`() {
        assertEquals(9.459308, EnglishMarkovModel.unseenTransitionCost.toDouble(), 0.000001)
        assertEquals(EnglishMarkovModel.unseenTransitionCost.toDouble(), EnglishGibberishDetector.score("12")!!, 0.0)
        assertEquals(EnglishMarkovModel.unseenTransitionCost.toDouble(), EnglishGibberishDetector.score("A!")!!, 0.0)
        assertTrue(EnglishGibberishDetector.isGibberish("!"))
        assertFalse(EnglishGibberishDetector.isGibberish("a"))
        assertTrue(EnglishGibberishDetector.isGibberish("😀"))
    }
}
