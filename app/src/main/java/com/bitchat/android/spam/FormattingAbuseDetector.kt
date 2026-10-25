package com.bitchat.android.spam

import java.text.Normalizer
import java.util.Locale

/** Stateless checks for a single message that can overwhelm text layout or fill the chat. */
internal object FormattingAbuseDetector {
    private const val MAX_INPUT_UNITS = 8_192
    private const val MAX_NORMALIZED_UNITS = 32_768
    private const val FNV_OFFSET = -3750763034362895579L
    private const val FNV_PRIME = 1099511628211L

    fun isAbusive(text: String): Boolean {
        // Check before allocating normalized strings or the repetition detector's code-point array.
        if (text.length > MAX_INPUT_UNITS) return true

        var codePoints = 0
        var marks = 0
        var markRun = 0
        var stackedRuns = 0
        var invisible = 0
        var previous = -1
        var sameRun = 0
        var whitespaceRun = 0
        var offset = 0
        while (offset < text.length) {
            val point = text.codePointAt(offset)
            offset += Character.charCount(point)
            codePoints++
            sameRun = if (point == previous) sameRun + 1 else 1
            previous = point
            if (sameRun >= 128) return true
            whitespaceRun = if (isWhitespace(point)) whitespaceRun + 1 else 0
            if (whitespaceRun >= 512) return true
            if (isInvisible(point)) invisible++
            if (isMark(point)) {
                marks++
                markRun++
                if (markRun >= 32) return true
                if (markRun == 8) stackedRuns++
            } else {
                markRun = 0
            }
        }
        if (stackedRuns >= 4 && marks >= 64 && marks.toDouble() / codePoints >= 0.4) return true
        if (invisible >= 128 && invisible.toDouble() / codePoints >= 0.6) return true

        // Analysis only: never rewrite the message that the caller displays or stores.
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKD).lowercase(Locale.ROOT)
        if (normalized.length > MAX_NORMALIZED_UNITS) return true
        val compact = StringBuilder()
        val rendered = StringBuilder()
        val words = ArrayList<Long>()
        var wordHash = FNV_OFFSET
        var inWord = false
        offset = 0
        while (offset < normalized.length) {
            val point = normalized.codePointAt(offset)
            offset += Character.charCount(point)
            if (isMark(point) || isInvisible(point)) continue
            if (!isWhitespace(point)) rendered.appendCodePoint(point)
            if (Character.isLetterOrDigit(point)) {
                compact.appendCodePoint(point)
                wordHash = (wordHash xor point.toLong()) * FNV_PRIME
                inWord = true
            } else if (inWord) {
                words.add(wordHash)
                wordHash = FNV_OFFSET
                inWord = false
            }
        }
        if (inWord) words.add(wordHash)

        // Symbol and emoji walls, including messages with no letters or digits at all.
        if (rendered.length >= 512 && repetitiveCharacters(rendered, 0.90)) return true
        if (compact.length < 256) return false
        if (words.size >= 48 && repetitiveWords(words)) return true
        // Also covers scripts whose words do not use spaces.
        return repetitiveCharacters(compact, 0.85)
    }

    private fun repetitiveWords(words: List<Long>): Boolean {
        val distinct = HashSet<Long>()
        val total = words.size - 3
        for (index in 0 until total) {
            var hash = FNV_OFFSET
            for (position in index until index + 4) hash = (hash xor words[position]) * FNV_PRIME
            distinct.add(hash)
        }
        return 1.0 - distinct.size.toDouble() / total >= 0.70
    }

    private fun repetitiveCharacters(text: CharSequence, threshold: Double): Boolean {
        val distinct = HashSet<Long>()
        val total = text.length - 4
        for (index in 0 until total) {
            var hash = FNV_OFFSET
            for (position in index until index + 5) hash = (hash xor text[position].code.toLong()) * FNV_PRIME
            distinct.add(hash)
        }
        return 1.0 - distinct.size.toDouble() / total >= threshold
    }

    private fun isWhitespace(point: Int): Boolean =
        Character.isWhitespace(point) || Character.isSpaceChar(point) || point == 0x85

    private fun isInvisible(point: Int): Boolean =
        Character.getType(point) == Character.FORMAT.toInt() ||
            (Character.isISOControl(point) && !isWhitespace(point))

    private fun isMark(point: Int): Boolean = when (Character.getType(point)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }
}
