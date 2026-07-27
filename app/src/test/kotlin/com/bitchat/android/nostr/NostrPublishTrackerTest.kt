package com.bitchat.android.nostr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NostrPublishTrackerTest {
    @Test
    fun `first relay acceptance completes publication`() = runBlocking {
        val tracker = NostrPublishTracker()
        val result = tracker.begin("event", setOf("relay-a", "relay-b"))

        tracker.record("event", "relay-a", accepted = true, message = null)

        assertEquals(NostrPublishResult.Accepted("relay-a"), result.await())
    }

    @Test
    fun `publication is rejected only after every target rejects`() = runBlocking {
        val tracker = NostrPublishTracker()
        val result = tracker.begin("event", setOf("relay-a", "relay-b"))

        tracker.record("event", "relay-a", accepted = false, message = "duplicate")
        assertFalse(result.isCompleted)
        tracker.record("event", "relay-b", accepted = false, message = "blocked")

        assertEquals(
            NostrPublishResult.Rejected(
                mapOf("relay-a" to "duplicate", "relay-b" to "blocked")
            ),
            result.await()
        )
    }
}
