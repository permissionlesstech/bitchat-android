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
    fun `duplicate event publishes retain independent delivery state`() {
        val queue = NostrPendingEventQueue(capacity = 4)
        val signedEvent = event("same")
        val firstId = requireNotNull(
            queue.enqueue(signedEvent, listOf("relay-a", "relay-b"), liveLocationToken = null)
        )
        val secondId = requireNotNull(
            queue.enqueue(signedEvent, listOf("relay-a"), liveLocationToken = null)
        )
        assertNotEquals(firstId, secondId)

        queue.markDelivered(firstId, "relay-a")

        assertEquals(
            listOf(secondId),
            queue.pendingForRelay("relay-a").map { it.queueId }
        )
        assertEquals(
            listOf(firstId),
            queue.pendingForRelay("relay-b").map { it.queueId }
        )

        queue.markDelivered(firstId, "relay-b")
        assertEquals(1, queue.size())
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

    @Test
    fun `revocation invalidates queued and already selected deliveries even after reenable`() {
        val queue = NostrPendingEventQueue(4)
        var generation = 1
        val captured = generation
        queue.enqueue(event("bridge"), listOf("relay"), null) { generation == captured }
        val selected = queue.pendingForRelay("relay").single()
        generation++ // Disable.
        generation++ // Re-enable; old permission must remain invalid.
        org.junit.Assert.assertFalse(selected.publicationAllowed())
        assertEquals(emptyList<NostrPendingEventQueue.Delivery>(), queue.pendingForRelay("relay"))
        assertEquals(0, queue.size())
    }

    @Test
    fun `removing a relay drops its pending work without losing other deliveries`() {
        val queue = NostrPendingEventQueue(4)
        queue.enqueue(event("shared"), listOf("removed", "retained"), null)
        queue.enqueue(event("removed-only"), listOf("removed"), null)
        queue.removeRelay("removed")
        assertEquals(0, queue.pendingForRelay("removed").size)
        assertEquals(listOf("shared"), queue.pendingForRelay("retained").map { it.event.content })
        assertEquals(1, queue.size())
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
