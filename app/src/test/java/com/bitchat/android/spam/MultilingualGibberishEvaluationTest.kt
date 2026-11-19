package com.bitchat.android.spam

import com.google.gson.GsonBuilder
import java.io.File
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Disjoint static translations and invented chat; these rates are not population estimates. */
class MultilingualGibberishEvaluationTest {
    @Test
    fun `measure each language and independently randomized native script text`() {
        val samples = LanguageHeldOutSamples.samples
        assertEquals(32, samples.map { it.language }.toSet().size)
        assertEquals(128, samples.count { it.synthetic })
        val results = samples.map { sample ->
            mapOf("id" to sample.id, "language" to sample.language, "content" to sample.content,
                "synthetic" to sample.synthetic, "score" to EnglishGibberishDetector.score(sample.content),
                "hidden" to EnglishGibberishDetector.isGibberish(sample.content))
        }
        val random = Random(1007)
        val floods = samples.groupBy { it.language }.mapValues { (_, rows) ->
            val alphabet = rows.flatMap { it.content.codePoints().toArray().toList() }
                .filter { Character.isLetter(it) }.distinct().sorted()
            val hidden = (0 until 100).count {
                val text = buildString { repeat(96) { appendCodePoint(alphabet[random.nextInt(alphabet.size)]) } }
                EnglishGibberishDetector.isGibberish(text)
            }
            mapOf("total" to 100, "hidden" to hidden)
        }
        val summary = results.groupBy { it.getValue("language") }.mapValues { (_, rows) ->
            mapOf("total" to rows.size, "hidden" to rows.count { it["hidden"] == true },
                "syntheticTotal" to rows.count { it["synthetic"] == true },
                "syntheticHidden" to rows.count { it["synthetic"] == true && it["hidden"] == true })
        }
        assertTrue(results.all { (it["score"] as? Double)?.isFinite() == true })
        summary.forEach { (language, counts) -> println("LANGUAGE language=$language counts=$counts flood=${floods[language]}") }
        System.getProperty("spam.evaluation.dir")?.let { directory ->
            val output = File(directory).apply { mkdirs() }
            val target = System.getProperty("spam.evaluation.target", "jvm")
            File(output, "languages-$target.json").writeText(
                GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(
                    mapOf("samples" to results, "summary" to summary, "randomizedCharacters" to floods)
                ) + "\n"
            )
        }
        // Save measurements first: a historical baseline is expected to fail these new quality guards.
        assertTrue(summary.values.all { it.getValue("syntheticHidden") < it.getValue("syntheticTotal") })
        assertTrue(summary.values.sumOf { it.getValue("syntheticHidden") } <= 12)
        assertTrue(summary.values.sumOf { it.getValue("hidden") } <= samples.size / 10)
        assertTrue(floods.values.all { it.getValue("hidden") >= 95 })
    }
}
