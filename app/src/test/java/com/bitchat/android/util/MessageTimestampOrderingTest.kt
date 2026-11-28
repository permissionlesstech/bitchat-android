package com.bitchat.android.util

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageTimestampOrderingTest {

    private data class SampleMessage(
        val id: String,
        val packetTs: Long,
        val receivedAt: Long
    )

    @Test
    fun ordersOutOfOrderDeliveriesByPacketTimestamp() {
        val now = 1_700_000_000_000L
        val olderSentLaterArrived = SampleMessage("m1", now - 10_000L, now)
        val newerSentEarlierArrived = SampleMessage("m2", now - 2_000L, now - 1_000L)

        val sorted = listOf(newerSentEarlierArrived, olderSentLaterArrived).sortedWith(
            MessageTimestampOrdering.comparator({ it.packetTs }, { it.receivedAt }, { it.id })
        )

        assertEquals(listOf("m1", "m2"), sorted.map { it.id })
    }

    @Test
    fun clampsFarFutureAndFarPastSpoofedTimestamps() {
        val now = 1_700_000_000_000L
        val farFuture = MessageTimestampOrdering.effectiveTimestampMs(now + 86_400_000L, now)
        val farPast = MessageTimestampOrdering.effectiveTimestampMs(now - 86_400_000L, now)

        assertEquals(now + MessageTimestampOrdering.MAX_CLOCK_SKEW_MS, farFuture)
        assertEquals(now - MessageTimestampOrdering.MAX_CLOCK_SKEW_MS, farPast)
    }

    @Test
    fun fallsBackToReceivedAtWhenPacketTimestampIsNonPositive() {
        val now = 1_700_000_000_000L
        assertEquals(now, MessageTimestampOrdering.effectiveTimestampMs(0L, now))
        assertEquals(now, MessageTimestampOrdering.effectiveTimestampMs(-1L, now))
    }
}
