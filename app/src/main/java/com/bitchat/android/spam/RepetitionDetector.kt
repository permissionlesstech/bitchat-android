package com.bitchat.android.spam

/**
 * Character autocorrelation measures for repeated-content spam.
 *
 * Text is compared as Unicode code points after [normalize], so case, spacing and emoji
 * surrogate pairs don't hide a repeat.
 */
internal object RepetitionDetector {

    private val WHITESPACE = Regex("\\s+")

    /** Lowercases, collapses whitespace and caps the length to [maxLength] code points. */
    fun normalize(text: String, maxLength: Int): IntArray {
        val codePoints = text.lowercase().replace(WHITESPACE, " ").trim().codePoints().toArray()
        return if (codePoints.size > maxLength) codePoints.copyOf(maxLength) else codePoints
    }

    /**
     * Highest autocorrelation of [text] with itself over shifts 1..n/2: the fraction of
     * overlapping positions where `text[i] == text[i + lag]`. A pattern repeated back to back
     * ("aaaa", "buy now buy now") scores 1.0; ordinary prose stays well below 0.3.
     */
    fun selfSimilarity(text: IntArray): Double {
        val n = text.size
        var best = 0.0
        for (lag in 1..n / 2) {
            val overlap = n - lag
            var matches = 0
            for (i in 0 until overlap) if (text[i] == text[i + lag]) matches++
            val score = matches.toDouble() / overlap
            if (score > best) best = score
        }
        return best
    }

    /**
     * Highest cross-correlation of two texts over every alignment: matching positions divided by
     * the longer length, so 1.0 means identical and a short text never matches a long one.
     */
    fun crossSimilarity(a: IntArray, b: IntArray): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val longest = maxOf(a.size, b.size)
        var best = 0
        for (shift in -(b.size - 1) until a.size) {
            val from = maxOf(0, shift)
            val to = minOf(a.size, b.size + shift)
            if (to - from <= best) continue
            var matches = 0
            for (i in from until to) if (a[i] == b[i - shift]) matches++
            if (matches > best) best = matches
        }
        return best.toDouble() / longest
    }
}
