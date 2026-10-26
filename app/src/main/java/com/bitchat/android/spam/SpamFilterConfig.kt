package com.bitchat.android.spam

/**
 * Tunables for [SpamFilter]. These are code-level knobs only; the user can just turn the
 * filter on or off.
 */
data class SpamFilterConfig(
    /** Messages per sender: a burst of 6, then one more every 10 seconds. */
    val messageBucket: TokenBucketConfig = TokenBucketConfig(
        capacity = 6.0,
        refillPerSecond = 1.0 / 10.0
    ),
    /** Characters per sender: 1200 per minute, refilled continuously (20 per second). */
    val characterBucket: TokenBucketConfig = TokenBucketConfig(
        capacity = 1200.0,
        refillPerSecond = 1200.0 / 60.0
    ),
    /** Links per sender: 2 per minute, refilled continuously (one every 30 seconds). */
    val linkBucket: TokenBucketConfig = TokenBucketConfig(
        capacity = 2.0,
        refillPerSecond = 2.0 / 60.0
    ),
    val repetition: RepetitionConfig = RepetitionConfig(),
    /**
     * How far in the past a message timestamp is trusted. Relay backfill (geohash subscribes to
     * the last hour) and mesh sync deliver history in bursts, so buckets run on message time;
     * older or future timestamps are clamped into [now - maxBackdateMs, now].
     */
    val maxBackdateMs: Long = 60 * 60 * 1000L,
    /**
     * Geohash senders not yet established in a channel share this bucket, one per channel:
     * a burst of 6, then 6 per minute. Fresh identities cost nothing, so this caps what a
     * flood of them can show.
     */
    val newSenderPool: TokenBucketConfig = TokenBucketConfig(
        capacity = 6.0,
        refillPerSecond = 6.0 / 60.0
    ),
    /**
     * A geohash sender is established in a channel once one of its admitted messages there is
     * at least this much older (message time) than the current one. Zero: established from the
     * first admitted message on. Longer delays hide ordinary chat in busy channels.
     */
    val establishAfterMs: Long = 0L,
    /** When false, only [SpamFilter.check]'s `proven` senders skip the new-sender pool. */
    val establishByTime: Boolean = true,
    /** Least recently active geohash channels beyond this lose their pool and established set. */
    val maxPoolChannels: Int = 64,
    /** Established senders remembered per channel. */
    val maxEstablishedPerChannel: Int = 1_000,
    /** Least recently active senders beyond this are forgotten. */
    val maxTrackedSenders: Int = 1_000,
    /** Verdicts remembered by message ID so multi-transport duplicates are judged once. */
    val maxRememberedVerdicts: Int = 4_000
)

data class TokenBucketConfig(
    val capacity: Double,
    val refillPerSecond: Double
)

data class RepetitionConfig(
    /** Messages shorter than this (in characters) are never checked for repeated characters. */
    val minRepeatedCharactersLength: Int = 32,
    /** Hide when some shift of the message matches itself at this fraction of positions. */
    val repeatedCharactersThreshold: Double = 0.85,
    /** Messages shorter than this are never compared with the sender's history. */
    val minRepeatedMessageLength: Int = 10,
    /** Two messages count as the same when their best alignment matches this fraction. */
    val repeatedMessageSimilarity: Double = 0.8,
    /** How many near-copies of one text a sender may post within [historyWindowMs]. */
    val allowedRepeats: Int = 1,
    /**
     * How many near-copies of a text *other* senders may already have posted within
     * [historyWindowMs] before it is hidden. Catches spam spread over many identities.
     */
    val crossUserAllowedRepeats: Int = 2,
    /** Messages shorter than this are never compared across senders; short replies coincide. */
    val crossUserMinLength: Int = 16,
    /** Recent messages from all senders kept for the cross-user comparison. */
    val crossUserHistorySize: Int = 200,
    /** Recent messages per sender kept for comparison. */
    val historySize: Int = 10,
    val historyWindowMs: Long = 10 * 60 * 1000L,
    /** Longer messages are analyzed on their first N characters only, bounding CPU per message. */
    val maxAnalyzedLength: Int = 1_000,
    /** Messages are compared with others on their first N characters only. */
    val maxComparedLength: Int = 280,
    /** Correlation peaks combined per comparison; extra peaks absorb inserted or deleted text. */
    val maxAlignmentPeaks: Int = 3,
    /** Extra peaks only count runs of at least this many matching characters. */
    val minPeakRun: Int = 3,
    /** Pairs sharing fewer trigrams than this fraction skip the full comparison. */
    val minSharedTrigrams: Double = 0.25
)
