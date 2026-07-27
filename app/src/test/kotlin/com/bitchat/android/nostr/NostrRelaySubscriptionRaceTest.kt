package com.bitchat.android.nostr

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NostrRelaySubscriptionRaceTest {
    @Test
    fun immediateEventDuringReqUsesCommitAwareHandler() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val deduplicator = NostrEventDeduplicator(maxCapacity = 8)
        val manager = NostrRelayManager(scope, deduplicator)
        val subscriptionId = "commit-aware-race"
        val event = NostrEvent(
            id = "7a".repeat(32),
            pubkey = "7b".repeat(32),
            createdAt = 1,
            kind = 1060,
            tags = emptyList(),
            content = "ciphertext",
            sig = "signature"
        )
        var processed = 0
        installConnection(
            manager = manager,
            relayUrl = RELAY_URL,
            webSocket = ImmediateEventWebSocket {
                deliverEvent(manager, subscriptionId, event)
            }
        )

        manager.subscribeAfterSuccessfulProcessing(
            filter = NostrFilter(kinds = listOf(1060)),
            id = subscriptionId
        ) {
            processed += 1
            true
        }

        assertEquals(1, processed)
        assertTrue(deduplicator.contains(event.id))
    }

    @Suppress("UNCHECKED_CAST")
    private fun installConnection(
        manager: NostrRelayManager,
        relayUrl: String,
        webSocket: WebSocket
    ) {
        val field = NostrRelayManager::class.java.getDeclaredField("connections")
        field.isAccessible = true
        val connections =
            field.get(manager) as ConcurrentHashMap<String, WebSocket>
        connections[relayUrl] = webSocket
    }

    private fun deliverEvent(
        manager: NostrRelayManager,
        subscriptionId: String,
        event: NostrEvent
    ) {
        val method = NostrRelayManager::class.java.getDeclaredMethod(
            "handleMessage",
            String::class.java,
            String::class.java
        )
        method.isAccessible = true
        method.invoke(
            manager,
            """["EVENT","$subscriptionId",${event.toJsonString()}]""",
            RELAY_URL
        )
    }

    private class ImmediateEventWebSocket(
        private val onRequest: () -> Unit
    ) : WebSocket {
        override fun request(): Request =
            Request.Builder().url("https://relay.example").build()

        override fun queueSize(): Long = 0L

        override fun send(text: String): Boolean {
            onRequest()
            return true
        }

        override fun send(bytes: ByteString): Boolean = false

        override fun close(code: Int, reason: String?): Boolean = true

        override fun cancel() = Unit
    }

    companion object {
        private const val RELAY_URL = "wss://relay.example"
    }
}
