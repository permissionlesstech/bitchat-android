package com.bitchat.android.spam

import com.google.gson.GsonBuilder
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Measures known legitimate synthetic inputs; deliberately does not assume zero false positives. */
class EnglishGibberishFalsePositiveEvaluationTest {
    private data class Result(
        val id: String,
        val category: String,
        val content: String,
        val score: Double?,
        val modelFlagged: Boolean,
        val filterVerdict: SpamVerdict
    )

    @Test
    fun `measure legitimate numeric symbolic and multilingual messages without hiding false positives`() {
        val samples = LegitimateCharacterSamples.samples
        assertEquals(220, samples.size)
        assertEquals(samples.size, samples.map { it.id }.toSet().size)
        assertEquals(4.2, EnglishGibberishDetector.THRESHOLD, 0.0)
        val results = samples.map { sample ->
            val score = EnglishGibberishDetector.score(sample.content)
            val flagged = EnglishGibberishDetector.isGibberish(sample.content)
            val verdict = SpamFilter(clock = { 1_000_000_000L }).check(
                "mesh:synthetic-evaluation", sample.id, sample.content, 1_000_000_000L
            )
            if (flagged) assertEquals(sample.id, SpamVerdict.ENGLISH_GIBBERISH, verdict)
            Result(sample.id, sample.category, sample.content, score, flagged, verdict)
        }
        val summary = results.groupBy { it.category }.mapValues { (_, group) ->
            mapOf(
                "total" to group.size,
                "modelFlagged" to group.count { it.modelFlagged },
                "filterHidden" to group.count { it.filterVerdict.isSpam },
                "unscored" to group.count { it.score == null }
            )
        }
        assertEquals(samples.size, summary.values.sumOf { it.getValue("total") })
        assertTrue(results.all { it.score == null || it.score.isFinite() })
        summary.forEach { (category, counts) -> println("LEGITIMATE category=$category counts=$counts") }
        System.getProperty("spam.evaluation.dir")?.let { directory ->
            val output = File(directory).apply { mkdirs() }
            val target = System.getProperty("spam.evaluation.target", "jvm")
            File(output, "legitimate-$target.json").writeText(
                GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(
                    mapOf("samples" to results, "summary" to summary)
                ) + "\n"
            )
        }
    }
}
