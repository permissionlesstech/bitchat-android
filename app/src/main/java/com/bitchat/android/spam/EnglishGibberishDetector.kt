package com.bitchat.android.spam

/**
 * A fixed English character-bigram model, trained offline on synthetic examples.
 *
 * Scores every character in a bounded prefix, including short replies and technical content.
 * Characters outside the trained alphabet receive the highest existing transition cost.
 * A high score measures departure from the English training data, not a probability of abuse.
 */
internal object EnglishGibberishDetector {
    internal const val THRESHOLD = 4.2
    private const val MAX_INPUT_LENGTH = 4_096
    private const val MAX_SCORED_LENGTH = 1_000
    private const val SPACE = 26
    private const val UNKNOWN = -1
    private const val NO_PREVIOUS = -2

    fun isGibberish(content: String): Boolean = score(content)?.let { it >= THRESHOLD } ?: false

    /** Average transition cost, or null only for empty input. Whitespace runs are collapsed. */
    internal fun score(content: String): Double? {
        var previous = NO_PREVIOUS
        var cost = 0.0
        var transitions = 0
        var scoredLength = 0
        var offset = 0
        val limit = minOf(content.length, MAX_INPUT_LENGTH)
        while (offset < limit && scoredLength < MAX_SCORED_LENGTH) {
            val point = content.codePointAt(offset)
            offset += Character.charCount(point)
            val current = when {
                point in 'a'.code..'z'.code -> point - 'a'.code
                point in 'A'.code..'Z'.code -> point - 'A'.code
                Character.isWhitespace(point) || Character.isSpaceChar(point) -> SPACE
                else -> UNKNOWN
            }
            if (current == SPACE && previous == SPACE) continue
            if (previous != NO_PREVIOUS) {
                cost += if (previous == UNKNOWN || current == UNKNOWN) {
                    EnglishMarkovModel.unseenTransitionCost
                } else {
                    EnglishMarkovModel.cost(previous, current)
                }
                transitions++
            }
            previous = current
            scoredLength++
        }
        return when {
            transitions > 0 -> cost / transitions
            previous == UNKNOWN -> EnglishMarkovModel.unseenTransitionCost.toDouble()
            previous != NO_PREVIOUS -> EnglishMarkovModel.cost(SPACE, previous).toDouble()
            else -> null
        }
    }
}
