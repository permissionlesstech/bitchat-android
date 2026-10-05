package com.bitchat.android.nostr

import com.bitchat.android.util.AppConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NostrTimestampPolicyTest {

    @Test
    fun `fixed NIP17 vectors pin receive policy independently of constants`() {
        val stream = requireNotNull(javaClass.getResourceAsStream("/contracts/nip17-timestamp-v1.csv"))
        stream.bufferedReader().useLines { lines ->
            lines.drop(1).forEach { line ->
                val fields = line.split(',')
                assertEquals(fields[0], fields[4].toBooleanStrict(),
                    NostrTimestampPolicy.isPlausibleRumorTimestamp(fields[2].toInt(), fields[1].toLong()))
                assertEquals(fields[0], fields[5].toBooleanStrict(),
                    NostrTimestampPolicy.isAcceptableGiftWrapTimestamp(fields[3].toInt(), fields[1].toLong()))
            }
        }
    }

    @Test
    fun `subscription cutoff includes randomized day-old messages`() {
        assertEquals(1_699_739_900_000L, NostrTimestampPolicy.giftWrapSinceMillis(1_700_000_000_000L))
    }

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
