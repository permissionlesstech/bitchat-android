package com.bitchat.android.nostr

import com.bitchat.android.util.AppConstants
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NostrTimestampPolicyTest {

    private val now = 1_700_000_000L
    private val skew = AppConstants.Nostr.DM_MAX_CLOCK_SKEW_SECONDS
    private val lookback = AppConstants.Nostr.DM_SUBSCRIBE_LOOKBACK_SECONDS
    private val giftWrapMax = AppConstants.Nostr.DM_GIFT_WRAP_MAX_AGE_SECONDS

    @Test
    fun `rumor timestamp at now is accepted`() {
        assertTrue(NostrTimestampPolicy.isPlausibleRumorTimestamp(now.toInt(), now))
    }

    @Test
    fun `rumor timestamp within lookback is accepted`() {
        val ts = (now - lookback).toInt()
        assertTrue(NostrTimestampPolicy.isPlausibleRumorTimestamp(ts, now))
    }

    @Test
    fun `rumor timestamp just inside skew past lookback is accepted`() {
        val ts = (now - lookback - skew).toInt()
        assertTrue(NostrTimestampPolicy.isPlausibleRumorTimestamp(ts, now))
    }

    @Test
    fun `rumor timestamp older than lookback plus skew is rejected`() {
        val ts = (now - lookback - skew - 1).toInt()
        assertFalse(NostrTimestampPolicy.isPlausibleRumorTimestamp(ts, now))
    }

    @Test
    fun `future-dated rumor within skew is accepted`() {
        val ts = (now + skew).toInt()
        assertTrue(NostrTimestampPolicy.isPlausibleRumorTimestamp(ts, now))
    }

    @Test
    fun `future-dated rumor beyond skew is rejected`() {
        val ts = (now + skew + 1).toInt()
        assertFalse(NostrTimestampPolicy.isPlausibleRumorTimestamp(ts, now))
    }

    @Test
    fun `future-dated gift wrap beyond skew is rejected`() {
        val createdAt = (now + skew + 1).toInt()
        assertFalse(NostrTimestampPolicy.isAcceptableGiftWrapTimestamp(createdAt, now))
    }

    @Test
    fun `gift wrap within randomization ceiling is accepted`() {
        val createdAt = (now - giftWrapMax).toInt()
        assertTrue(NostrTimestampPolicy.isAcceptableGiftWrapTimestamp(createdAt, now))
    }

    @Test
    fun `gift wrap older than randomization ceiling is rejected`() {
        val createdAt = (now - giftWrapMax - 1).toInt()
        assertFalse(NostrTimestampPolicy.isAcceptableGiftWrapTimestamp(createdAt, now))
    }
}
