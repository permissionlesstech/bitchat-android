package com.bitchat.android.spam

import java.text.Normalizer
import java.util.Locale

/** Fixed offline language models; never learns, downloads weights, or consults the UI locale. */
internal object MultilingualGibberishDetector {
    internal const val THRESHOLD = 4.2
    private const val MAX_INPUT_LENGTH = 4096
    private const val MAX_SCORED_LENGTH = 1000

    private val latin = listOf("en", "de", "es", "eu", "fil", "fr", "id", "it", "mg", "ms", "nl", "pl", "pt", "sv", "tr", "vi")
    private val groups = mapOf(
        "latin" to latin, "cyrillic" to listOf("ru", "uk"),
        "arabic" to listOf("ar", "fa", "pa", "ur"), "devanagari" to listOf("hi", "ne"),
        "bengali" to listOf("bn"), "tamil" to listOf("ta"), "thai" to listOf("th"),
        "georgian" to listOf("ka"), "hebrew" to listOf("he"), "hangul" to listOf("ko"),
        "cjk" to listOf("ja", "zh")
    )

    fun isGibberish(content: String): Boolean = score(content)?.let { it >= THRESHOLD } ?: false

    /** Costs are divided by each model's calibrated threshold, then expressed on the old 4.2 scale. */
    fun score(content: String): Double? {
        val points = normalize(content)
        if (points.isEmpty()) return null
        return candidates(points).minOf { language ->
            val model = LanguageModelPack.model(language)
            model.score(points) / model.threshold * THRESHOLD
        }
    }

    /** The dominant script limits candidates; every character still contributes to their score. */
    internal fun candidates(points: IntArray): List<String> {
        val counts = linkedMapOf<String, Int>()
        points.forEach { point ->
            if (Character.isLetter(point)) {
                val group = when (Character.UnicodeScript.of(point)) {
                    Character.UnicodeScript.LATIN -> "latin"
                    Character.UnicodeScript.CYRILLIC -> "cyrillic"
                    Character.UnicodeScript.ARABIC -> "arabic"
                    Character.UnicodeScript.DEVANAGARI -> "devanagari"
                    Character.UnicodeScript.BENGALI -> "bengali"
                    Character.UnicodeScript.TAMIL -> "tamil"
                    Character.UnicodeScript.THAI -> "thai"
                    Character.UnicodeScript.GEORGIAN -> "georgian"
                    Character.UnicodeScript.HEBREW -> "hebrew"
                    Character.UnicodeScript.HANGUL -> "hangul"
                    Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
                    Character.UnicodeScript.KATAKANA -> "cjk"
                    else -> "unknown"
                }
                counts[group] = counts.getOrDefault(group, 0) + 1
            }
        }
        val dominant = counts.maxByOrNull { it.value }?.key ?: return listOf("en")
        if (dominant == "cjk" && points.any {
                Character.UnicodeScript.of(it) == Character.UnicodeScript.HIRAGANA ||
                    Character.UnicodeScript.of(it) == Character.UnicodeScript.KATAKANA
            }) return listOf("ja")
        return groups[dominant] ?: listOf("en")
    }

    internal fun normalize(content: String): IntArray {
        // Bound the raw prefix before Unicode normalization can expand it. Preserve surrogate pairs.
        var end = minOf(content.length, MAX_INPUT_LENGTH)
        if (end < content.length && end > 0 && Character.isHighSurrogate(content[end - 1])) end--
        val text = Normalizer.normalize(content.substring(0, end), Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val points = IntArray(minOf(text.length, MAX_SCORED_LENGTH))
        var count = 0
        var offset = 0
        fun append(point: Int) {
            if (count < points.size) points[count++] = point
        }
        while (offset < text.length && count < points.size) {
            val point = text.codePointAt(offset)
            offset += Character.charCount(point)
            when {
                Character.isWhitespace(point) || Character.isSpaceChar(point) -> {
                    if (count == 0 || points[count - 1] != ' '.code) append(' '.code)
                }
                else -> append(point)
            }
        }
        return if (count == points.size) points else points.copyOf(count)
    }
}
