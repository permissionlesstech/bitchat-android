package com.bitchat.android.spam

import java.text.Normalizer

/**
 * Character autocorrelation measures for repeated-content spam.
 *
 * Text is compared as Unicode code points after [normalize], so case, spacing, look-alike
 * letters, accents and invisible characters don't hide a repeat.
 */
internal object RepetitionDetector {

    /**
     * Folds a message to what a reader sees, for comparison only (never for display): NFKD
     * turns fullwidth and math-styled letters into plain ones and splits off accents, then
     * combining marks and invisible characters are dropped, whitespace runs become one space
     * and the result is lowercased and capped to [maxLength] code points.
     */
    fun normalize(text: String, maxLength: Int): IntArray {
        // Disguises at most a few code units per visible character; bound the work up front.
        val head = if (text.length > maxLength * 4) text.substring(0, maxLength * 4) else text
        val decomposed = Normalizer.normalize(head, Normalizer.Form.NFKD)
        val out = IntArray(minOf(maxLength, decomposed.length))
        var size = 0
        var pendingSpace = false
        var offset = 0
        while (offset < decomposed.length && size < out.size) {
            val point = decomposed.codePointAt(offset)
            offset += Character.charCount(point)
            when {
                isMark(point) || isInvisible(point) -> continue
                isWhitespace(point) -> pendingSpace = size > 0
                else -> {
                    if (pendingSpace && size < out.size - 1) out[size++] = ' '.code
                    pendingSpace = false
                    out[size++] = Character.toLowerCase(point)
                }
            }
        }
        return out.copyOf(size)
    }

    private fun isWhitespace(point: Int): Boolean =
        Character.isWhitespace(point) || Character.isSpaceChar(point)

    /** Zero-width and direction controls, soft hyphens and other non-printing characters. */
    private fun isInvisible(point: Int): Boolean =
        Character.getType(point) == Character.FORMAT.toInt() ||
            (Character.isISOControl(point) && !isWhitespace(point))

    private fun isMark(point: Int): Boolean = when (Character.getType(point)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt() -> true
        else -> false
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

    /** One diagonal of the cross-correlation: `b[j]` lined up under `a[j + shift]`. */
    data class Peak(val shift: Int, val matches: Int)

    /** How two texts line up: the peaks used and the share of the longer text they cover. */
    data class Alignment(val peaks: List<Peak>, val matches: Int, val score: Double)

    fun crossSimilarity(a: IntArray, b: IntArray, maxPeaks: Int = 3, minRun: Int = 3): Double =
        align(a, b, maxPeaks, minRun).score

    /**
     * Cross-correlation of two texts that tolerates small edits.
     *
     * The first peak is the classic correlation maximum, the shift where the most characters
     * match. An inserted or deleted character moves the rest of the text onto a neighboring
     * shift, so up to [maxPeaks] - 1 further peaks add matches from other shifts. Those count
     * only runs of at least [minRun] consecutive characters, so coincidental single letters
     * don't add up, and no character of either text is used twice. The score is the matched
     * characters over the longer length: 1.0 for identical texts, and a short text never
     * matches a long one.
     */
    fun align(a: IntArray, b: IntArray, maxPeaks: Int = 3, minRun: Int = 3): Alignment {
        if (a.isEmpty() || b.isEmpty()) return Alignment(emptyList(), 0, 0.0)
        val usedA = BooleanArray(a.size)
        val usedB = BooleanArray(b.size)
        val peaks = ArrayList<Peak>()
        var total = 0
        while (peaks.size < maxPeaks) {
            val run = if (peaks.isEmpty()) 1 else minRun
            var bestGain = 0
            var bestShift = 0
            for (shift in -(b.size - 1) until a.size) {
                val from = maxOf(0, -shift)
                val to = minOf(b.size, a.size - shift)
                if (to - from <= bestGain) continue
                val gain = runMatches(a, b, shift, from, to, usedA, usedB, run, mark = false)
                if (gain > bestGain) {
                    bestGain = gain
                    bestShift = shift
                }
            }
            if (bestGain < run) break
            val from = maxOf(0, -bestShift)
            val to = minOf(b.size, a.size - bestShift)
            runMatches(a, b, bestShift, from, to, usedA, usedB, run, mark = true)
            peaks += Peak(bestShift, bestGain)
            total += bestGain
        }
        return Alignment(peaks, total, total.toDouble() / maxOf(a.size, b.size))
    }

    /** Unused matches at [shift] that sit in runs of at least [minRun]; [mark] claims them. */
    private fun runMatches(
        a: IntArray,
        b: IntArray,
        shift: Int,
        from: Int,
        to: Int,
        usedA: BooleanArray,
        usedB: BooleanArray,
        minRun: Int,
        mark: Boolean
    ): Int {
        var gain = 0
        var runStart = -1
        for (j in from..to) {
            val hit = j < to && !usedB[j] && !usedA[j + shift] && a[j + shift] == b[j]
            if (hit) {
                if (runStart < 0) runStart = j
            } else if (runStart >= 0) {
                val length = j - runStart
                if (length >= minRun) {
                    gain += length
                    if (mark) for (k in runStart until j) {
                        usedB[k] = true
                        usedA[k + shift] = true
                    }
                }
                runStart = -1
            }
        }
        return gain
    }

    /** Distinct character trigrams as sorted hashes, for a cheap pre-check before [align]. */
    fun trigrams(text: IntArray): IntArray {
        if (text.size < 3) return IntArray(0)
        return IntArray(text.size - 2) { i -> (text[i] * 31 + text[i + 1]) * 31 + text[i + 2] }
            .distinct()
            .sorted()
            .toIntArray()
    }

    /**
     * Shared trigrams over the larger trigram set. Near-copies share most of theirs, unrelated
     * texts few, so pairs below a low bar can skip the quadratic [align]. Texts too short to
     * have trigrams always pass.
     */
    fun trigramOverlap(a: IntArray, b: IntArray): Double {
        if (a.isEmpty() || b.isEmpty()) return 1.0
        var i = 0
        var j = 0
        var shared = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> { shared++; i++; j++ }
                a[i] < b[j] -> i++
                else -> j++
            }
        }
        return shared.toDouble() / maxOf(a.size, b.size)
    }
}
