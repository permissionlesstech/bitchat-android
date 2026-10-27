package com.bitchat.android.spam

import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.Executors
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultilingualGibberishDetectorTest {
    private val junk = "qxjvtpk zmxqvfb jkvxzpq vxjqzmt qzpvxjk"

    @Test
    fun `fixed offline models cover every locale variant and use independent thresholds`() {
        assertEquals(35, LanguageModelPack.aliases.size)
        assertEquals(32, LanguageModelPack.aliases.values.toSet().size)
        assertEquals("pt", LanguageModelPack.aliases["pt-BR"])
        assertEquals("pa", LanguageModelPack.aliases["pa-PK"])
        assertEquals("zh", LanguageModelPack.aliases["zh-TW"])
        val thresholds = LanguageModelPack.aliases.values.toSet().map { LanguageModelPack.model(it).threshold }
        assertTrue(thresholds.all { it.isFinite() && it > 0 })
        assertTrue(thresholds.toSet().size > 25)
    }

    @Test
    fun `quantized score vectors are stable on phone and watch`() {
        val vectors = listOf(
            Triple("en", "This is a small example.", 2.4131708559782608),
            Triple("ar", "هذا مثال بسيط.", 3.444842998798077),
            Triple("ko", "간단한 예시입니다.", 3.259060329861111),
            Triple("ja", "これは例です。", 3.1487223307291665),
            Triple("zh", "这是一个示例。", 3.3002115885416665)
        )
        vectors.forEach { (language, text, expected) ->
            assertEquals(language, expected, LanguageModelPack.model(language).score(
                MultilingualGibberishDetector.normalize(text)
            ), 0.000000001)
        }
    }

    @Test
    fun `candidate models follow the dominant script independently of the display locale`() {
        fun candidates(text: String) = MultilingualGibberishDetector.candidates(MultilingualGibberishDetector.normalize(text))
        assertEquals(listOf("ja"), candidates("これは例です。"))
        assertEquals(listOf("ja", "zh"), candidates("这是一个示例。"))
        assertEquals(listOf("ar", "fa", "pa", "ur"), candidates("هذا مثال بسيط."))
        assertEquals(listOf("en"), candidates("123!"))
        assertTrue("en" in candidates("hello 日本"))
        assertEquals(listOf("en"), candidates("ԱԲԳԴ")) // Untrained scripts still receive a score.
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            assertEquals(EnglishGibberishDetector.score("THIS IS A SMALL EXAMPLE."),
                EnglishGibberishDetector.score("this is a small example."))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `unknown characters punctuation digits and short replies are all scored`() {
        val samples = EnglishGibberishSamples.technical + listOf("!", "a", "😀", "12", "A!", "👍", "ԱԲԳԴ")
        samples.forEach { assertTrue(it, EnglishGibberishDetector.score(it)?.isFinite() == true) }
        assertNull(EnglishGibberishDetector.score(""))
        assertTrue(EnglishGibberishDetector.isGibberish("\u0000".repeat(50)))
        assertTrue(EnglishGibberishDetector.isGibberish("319850631729468501743289"))
    }

    @Test
    fun `normalization handles compatibility letters composed accents Hangul and whitespace`() {
        assertEquals(EnglishGibberishDetector.score(junk), EnglishGibberishDetector.score(junk.uppercase()))
        assertEquals(EnglishGibberishDetector.score(junk), EnglishGibberishDetector.score(junk.replace(" ", "\n\t")))
        assertEquals(EnglishGibberishDetector.score("hello"), EnglishGibberishDetector.score("ｈｅｌｌｏ"))
        listOf("café", "Tiếng Việt", "간단한 예시입니다.").forEach { text ->
            assertArrayEquals(MultilingualGibberishDetector.normalize(text), MultilingualGibberishDetector.normalize(
                Normalizer.normalize(text, Normalizer.Form.NFD)
            ))
        }
    }

    @Test
    fun `adding a few foreign letters cannot exempt long random text`() {
        listOf(junk, junk.replace(" ", ""), "$junk 日本語", "$junk café", "$junk مرحبا", "$junk 👍")
            .forEach { assertTrue(it, EnglishGibberishDetector.isGibberish(it)) }
    }

    @Test
    fun `all held out English chat examples pass and fluent nonsense remains a limitation`() {
        assertEquals(emptyList<String>(), EnglishGibberishSamples.english.filter(EnglishGibberishDetector::isGibberish))
        assertFalse(EnglishGibberishDetector.isGibberish("The invisible banana politely explained the meaning of the purple teapot."))
    }

    @Test
    fun `normalization and model work stay bounded even for expanding Unicode and surrogate boundaries`() {
        assertEquals(1000, MultilingualGibberishDetector.normalize("가".repeat(5000)).size)
        assertEquals(1000, MultilingualGibberishDetector.normalize("ﷺ".repeat(5000)).size)
        assertTrue(EnglishGibberishDetector.isGibberish(junk.repeat(200)))
        assertTrue(EnglishGibberishDetector.score("x".repeat(4095) + "😀")!!.isFinite())
    }

    @Test
    fun `parallel scoring shares immutable pretrained weights`() {
        val expected = EnglishGibberishDetector.score("هذا مثال بسيط.")
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = (0 until 20).map { pool.submit<Double?> { EnglishGibberishDetector.score("هذا مثال بسيط.") } }
            results.forEach { assertEquals(expected, it.get()) }
        } finally {
            pool.shutdownNow()
        }
    }
}
