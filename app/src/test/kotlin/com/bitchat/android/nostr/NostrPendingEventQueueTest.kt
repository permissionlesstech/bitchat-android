package com.bitchat.android.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NostrPendingEventQueueTest {
    @Test
    fun `empty relay set is not queued`() {
        val queue = NostrPendingEventQueue(capacity = 2)

        assertNull(
            queue.enqueue(
                event("empty"),
                emptyList(),
                liveLocationToken = null,
                accountGeneration = 1L
            )
        )
        assertEquals(0, queue.size())
    }

    @Test
    fun `capacity evicts the oldest publish`() {
        val queue = NostrPendingEventQueue(capacity = 2)
        queue.enqueue(event("one"), listOf("relay"), null, accountGeneration = 1L)
        queue.enqueue(event("two"), listOf("relay"), null, accountGeneration = 1L)
        queue.enqueue(event("three"), listOf("relay"), null, accountGeneration = 1L)

        assertEquals(
            listOf("two", "three"),
            queue.pendingForRelay("relay", accountGeneration = 1L)
                .map { it.event.content }
        )
    }

    @Test
    fun `duplicate event publishes retain independent delivery state`() {
        val queue = NostrPendingEventQueue(capacity = 4)
        val signedEvent = event("same")
        val firstId = requireNotNull(
            queue.enqueue(
                signedEvent,
                listOf("relay-a", "relay-b"),
                liveLocationToken = null,
                accountGeneration = 1L
            )
        )
        val secondId = requireNotNull(
            queue.enqueue(
                signedEvent,
                listOf("relay-a"),
                liveLocationToken = null,
                accountGeneration = 1L
            )
        )
        assertNotEquals(firstId, secondId)

        queue.markDelivered(firstId, "relay-a")

        assertEquals(
            listOf(secondId),
            queue.pendingForRelay("relay-a", accountGeneration = 1L)
                .map { it.queueId }
        )
        assertEquals(
            listOf(firstId),
            queue.pendingForRelay("relay-b", accountGeneration = 1L)
                .map { it.queueId }
        )

        queue.markDelivered(firstId, "relay-b")
        assertEquals(1, queue.size())
    }

    @Test
    fun `privacy purge retains non-live publishes`() {
        val queue = NostrPendingEventQueue(capacity = 4)
        queue.enqueue(event("manual"), listOf("relay"), null, accountGeneration = 1L)
        queue.enqueue(event("live"), listOf("relay"), 42L, accountGeneration = 1L)

        queue.removeLiveLocationEvents(accountGeneration = 1L)

        assertEquals(
            listOf("manual"),
            queue.pendingForRelay("relay", accountGeneration = 1L)
                .map { it.event.content }
        )
    }

    @Test
    fun `old account deliveries are excluded after generation changes`() {
        val queue = NostrPendingEventQueue(capacity = 4)
        queue.enqueue(event("old"), listOf("relay"), null, accountGeneration = 1L)
        queue.enqueue(event("current"), listOf("relay"), null, accountGeneration = 2L)

        val deliveries = queue.pendingForRelay("relay", accountGeneration = 2L)

        assertEquals(listOf("current"), deliveries.map { it.event.content })
        assertEquals(listOf(2L), deliveries.map { it.accountGeneration })
    }

    @Test
    fun `stale privacy purge cannot remove replacement account deliveries`() {
        val queue = NostrPendingEventQueue(capacity = 4)
        queue.enqueue(event("old-live"), listOf("relay"), 11L, accountGeneration = 1L)
        queue.enqueue(event("new-live"), listOf("relay"), 22L, accountGeneration = 2L)

        queue.removeLiveLocationEvents(accountGeneration = 1L)

        assertEquals(
            listOf("new-live"),
            queue.pendingForRelay("relay", accountGeneration = 2L)
                .map { it.event.content }
        )
    }

    private fun event(content: String): NostrEvent {
        val privateKey = "0".repeat(63) + "1"
        return NostrEvent(
            pubkey = NostrCrypto.derivePublicKey(privateKey),
            createdAt = 1,
            kind = NostrKind.TEXT_NOTE,
            tags = emptyList(),
            content = content
        ).sign(privateKey)
    }
}
