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
    fun `multilingual controls are not classified as English gibberish`() {
        val rejected = EnglishGibberishSamples.multilingual.filter(EnglishGibberishDetector::isGibberish)
        println("Multilingual model controls=${EnglishGibberishSamples.multilingual.size} rejected=${rejected.size}")
        assertEquals(emptyList<String>(), rejected)
    }

    @Test
    fun `short replies and technical controls are left alone`() {
        EnglishGibberishSamples.technical.forEach { assertFalse(it, EnglishGibberishDetector.isGibberish(it)) }
        assertNull(EnglishGibberishDetector.score("ok thanks"))
        assertNull(EnglishGibberishDetector.score("`abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz`"))
    }

    @Test
    fun `unsupported scripts accents and combining marks cause abstention`() {
        assertNull(EnglishGibberishDetector.score("$junk 日本語"))
        assertNull(EnglishGibberishDetector.score("$junk café"))
        assertNull(EnglishGibberishDetector.score("$junk cafe\u0301"))
    }

    @Test
    fun `case whitespace and ordinary punctuation preserve the decision`() {
        assertTrue(EnglishGibberishDetector.isGibberish(junk.uppercase()))
        assertTrue(EnglishGibberishDetector.isGibberish(junk.replace(" ", "\n\t")))
        assertEquals(EnglishGibberishDetector.score(junk), EnglishGibberishDetector.score("($junk)!"))
    }

    @Test
    fun `a message needs enough letters but does not require word boundaries`() {
        assertNull(EnglishGibberishDetector.score("qxjvtpk zmxqvfb jkvxzpq"))
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
    fun `oversized input is left to the existing character budget`() {
        assertNull(EnglishGibberishDetector.score(junk.repeat(200)))
    }
}
