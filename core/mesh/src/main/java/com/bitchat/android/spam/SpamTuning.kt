package com.bitchat.android.spam

/**
 * The [SpamFilterConfig] knobs exposed in debug settings, in whole units that suit sliders.
 * [DEFAULT] matches the code defaults in [SpamFilterConfig].
 */
data class SpamTuning(
    val messageBurst: Int,
    val secondsPerMessage: Int,
    val charactersPerMinute: Int,
    val linksPerMinute: Int,
    val allowedRepeats: Int,
    val crossUserAllowedRepeats: Int,
    val crossUserMinLength: Int,
    val repeatSimilarityPercent: Int,
    val repeatWindowMinutes: Int,
    val selfMatchPercent: Int,
    val selfMatchMinLength: Int
) {
    fun toConfig(): SpamFilterConfig {
        val base = SpamFilterConfig()
        return base.copy(
            messageBucket = TokenBucketConfig(
                capacity = messageBurst.toDouble(),
                refillPerSecond = 1.0 / secondsPerMessage.coerceAtLeast(1)
            ),
            characterBucket = TokenBucketConfig(
                capacity = charactersPerMinute.toDouble(),
                refillPerSecond = charactersPerMinute / 60.0
            ),
            linkBucket = TokenBucketConfig(
                capacity = linksPerMinute.toDouble(),
                refillPerSecond = linksPerMinute / 60.0
            ),
            repetition = base.repetition.copy(
                allowedRepeats = allowedRepeats,
                crossUserAllowedRepeats = crossUserAllowedRepeats,
                crossUserMinLength = crossUserMinLength,
                repeatedMessageSimilarity = repeatSimilarityPercent / 100.0,
                historyWindowMs = repeatWindowMinutes * 60_000L,
                repeatedCharactersThreshold = selfMatchPercent / 100.0,
                minRepeatedCharactersLength = selfMatchMinLength
            )
        )
    }

    companion object {
        val DEFAULT: SpamTuning = SpamFilterConfig().let { config ->
            SpamTuning(
                messageBurst = config.messageBucket.capacity.toInt(),
                secondsPerMessage = Math.round(1.0 / config.messageBucket.refillPerSecond).toInt(),
                charactersPerMinute = config.characterBucket.capacity.toInt(),
                linksPerMinute = config.linkBucket.capacity.toInt(),
                allowedRepeats = config.repetition.allowedRepeats,
                crossUserAllowedRepeats = config.repetition.crossUserAllowedRepeats,
                crossUserMinLength = config.repetition.crossUserMinLength,
                repeatSimilarityPercent = Math.round(config.repetition.repeatedMessageSimilarity * 100).toInt(),
                repeatWindowMinutes = (config.repetition.historyWindowMs / 60_000L).toInt(),
                selfMatchPercent = Math.round(config.repetition.repeatedCharactersThreshold * 100).toInt(),
                selfMatchMinLength = config.repetition.minRepeatedCharactersLength
            )
        }
    }
}
