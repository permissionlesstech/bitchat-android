package com.bitchat.android.spam

/**
 * A fixed English character-bigram model, trained offline on synthetic examples.
 *
 * Scores sufficiently long alphabetic text, including unbroken strings. Unknown scripts,
 * short replies and technical tokens are left to the other spam rules. A high score means
 * unusual English character transitions, not a calibrated probability of abuse.
 */
internal object EnglishGibberishDetector {
    internal const val THRESHOLD = 4.2
    private const val MIN_LETTERS = 24
    private const val MAX_INPUT_LENGTH = 4_096
    private const val MAX_SCORED_LENGTH = 1_000
    private const val SPACE = 26

    fun isGibberish(content: String): Boolean = score(content)?.let { it >= THRESHOLD } ?: false

    /** Average negative log probability per transition, or null when there is too little evidence. */
    internal fun score(content: String): Double? {
        if (content.length > MAX_INPUT_LENGTH) return null
        // Do not project another script or accented language onto an English-only alphabet.
        var offset = 0
        while (offset < content.length) {
            val point = content.codePointAt(offset)
            if (point > 127 && (Character.isLetter(point) || isMark(point))) return null
            offset += Character.charCount(point)
        }

        var previous = -1
        var cost = 0.0
        var transitions = 0
        var letters = 0
        var scoredLength = 0
        var start = 0
        while (start < content.length && scoredLength < MAX_SCORED_LENGTH) {
            while (start < content.length && content[start].isWhitespace()) start++
            var end = start
            while (end < content.length && !content[end].isWhitespace()) end++
            var first = start
            var last = end
            while (first < last && isPunctuation(content[first])) first++
            while (last > first && isPunctuation(content[last - 1])) last--
            val isWord = first < last && (first until last).all {
                content[it] in 'a'..'z' || content[it] in 'A'..'Z' || content[it] == '\''
            } && (first until last).any { content[it].isLetter() }
            if (isWord) {
                if (previous >= 0) {
                    cost += EnglishMarkovModel.cost(previous, SPACE)
                    previous = SPACE
                    transitions++
                    scoredLength++
                }
                for (index in first until last) {
                    if (scoredLength >= MAX_SCORED_LENGTH) break
                    if (content[index] == '\'') continue
                    val current = content[index].lowercaseChar() - 'a'
                    if (previous >= 0) {
                        cost += EnglishMarkovModel.cost(previous, current)
                        transitions++
                    }
                    previous = current
                    letters++
                    scoredLength++
                }
            }
            start = end
        }
        return if (letters >= MIN_LETTERS && transitions > 0) {
            cost / transitions
        } else {
            null
        }
    }

    private fun isPunctuation(char: Char): Boolean = char in ".,!?;:()[]{}\"'“”‘’"

    private fun isMark(point: Int): Boolean = when (Character.getType(point)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }
}
