package com.bitchat.android.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NostrPendingEventQueueTest {
    @Test
    fun `empty relay set is not queued`() {
        val queue = NostrPendingEventQueue(capacity = 2)

        assertNull(queue.enqueue(event("empty"), emptyList(), liveLocationToken = null))
        assertEquals(0, queue.size())
    }

    @Test
    fun `capacity evicts the oldest publish`() {
        val queue = NostrPendingEventQueue(capacity = 2)
        queue.enqueue(event("one"), listOf("relay"), liveLocationToken = null)
        queue.enqueue(event("two"), listOf("relay"), liveLocationToken = null)
        queue.enqueue(event("three"), listOf("relay"), liveLocationToken = null)

        assertEquals(
            listOf("two", "three"),
            queue.pendingForRelay("relay").map { it.event.content }
        )
    }

    @Test
    fun `same envelope retries consolidate pending relays until acknowledged`() {
        val queue = NostrPendingEventQueue(capacity = 4)
        val envelope = event("same")
        val first = queue.enqueue(envelope, listOf("relay-a", "relay-b"), null)
        assertEquals(first, queue.enqueue(envelope, listOf("relay-a"), null))
        assertEquals(1, queue.size())
        queue.acknowledge(envelope.id, "relay-a")
        assertEquals(0, queue.pendingForRelay("relay-a").size)
        assertEquals(1, queue.pendingForRelay("relay-b").size)
        queue.acknowledge(envelope.id, "relay-b")
        assertEquals(0, queue.size())
    }

    @Test
    fun `same event with different privacy provenance remains independently revocable`() {
        val queue = NostrPendingEventQueue(capacity = 4)
        val envelope = event("same")
        queue.enqueue(envelope, listOf("relay"), null)
        queue.enqueue(envelope, listOf("relay"), 42L)
        queue.removeLiveLocationEvents()
        assertEquals(1, queue.size())
        assertNull(queue.pendingForRelay("relay").single().liveLocationToken)
    }

    @Test
    fun `privacy purge retains non-live publishes`() {
        val queue = NostrPendingEventQueue(capacity = 4)
        queue.enqueue(event("manual"), listOf("relay"), liveLocationToken = null)
        queue.enqueue(event("live"), listOf("relay"), liveLocationToken = 42L)

        queue.removeLiveLocationEvents()

        assertEquals(
            listOf("manual"),
            queue.pendingForRelay("relay").map { it.event.content }
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
