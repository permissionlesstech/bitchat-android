package com.bitchat.android.nostr

import com.bitchat.android.util.AppConstants

/**
 * Client-side timestamp windows for inbound Nostr DMs.
 *
 * Mirrors iOS `NostrInboundPipeline.isPlausibleRumorTimestamp`: a relay that
 * ignores the subscription `since` filter — or replays archived events — must
 * not inject stale or future-dated DMs. The inner rumor timestamp is the
 * sender's true send time; only the outer gift wrap is NIP-17-randomized.
 */
object NostrTimestampPolicy {

    /**
     * Accept an inner rumor `created_at` inside
     * `[now − lookback − skew, now + skew]`.
     */
    fun isPlausibleRumorTimestamp(
        tsSeconds: Int,
        nowSeconds: Long = System.currentTimeMillis() / 1000L
    ): Boolean {
        val age = nowSeconds - tsSeconds.toLong()
        val skew = AppConstants.Nostr.DM_MAX_CLOCK_SKEW_SECONDS
        val lookback = AppConstants.Nostr.DM_SUBSCRIBE_LOOKBACK_SECONDS
        return age >= -skew && age <= lookback + skew
    }

    /**
     * Accept an outer gift-wrap `created_at` that is not in the far future and
     * not older than the NIP-17 randomization ceiling plus skew.
     */
    fun isAcceptableGiftWrapTimestamp(
        createdAtSeconds: Int,
        nowSeconds: Long = System.currentTimeMillis() / 1000L
    ): Boolean {
        val age = nowSeconds - createdAtSeconds.toLong()
        val skew = AppConstants.Nostr.DM_MAX_CLOCK_SKEW_SECONDS
        val maxAge = AppConstants.Nostr.DM_GIFT_WRAP_MAX_AGE_SECONDS
        return age >= -skew && age <= maxAge
    }
}
