package com.bitchat.android.nostr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NostrRelayConnectionEpochTest {
    @Test
    fun `disconnect invalidates already queued connect work before reconnect`() {
        val dispatcher = StandardTestDispatcher()
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        val openedUrls = mutableListOf<String>()
        val manager = NostrRelayManager(
            scope = scope,
            eventDeduplicator = NostrEventDeduplicator(maxCapacity = 8),
            webSocketFactory = { request, _ ->
                openedUrls += request.url.host
                RecordingWebSocket(request)
            }
        )

        try {
            manager.connect()
            manager.disconnect()
            replaceRelays(manager, listOf(FRESH_RELAY_URL))
            manager.connect()

            dispatcher.scheduler.runCurrent()

            assertEquals(listOf(FRESH_RELAY_HOST), openedUrls)
        } finally {
            manager.disconnect()
            scope.cancel()
        }
    }

    @Test
    fun `message callback from replaced socket cannot enter a current subscription`() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val deduplicator = NostrEventDeduplicator(maxCapacity = 8)
        var listener: WebSocketListener? = null
        lateinit var originalSocket: RecordingWebSocket
        val manager = NostrRelayManager(
            scope = scope,
            eventDeduplicator = deduplicator,
            webSocketFactory = { request, createdListener ->
                listener = createdListener
                RecordingWebSocket(request).also { originalSocket = it }
            }
        )
        replaceRelays(manager, listOf(FRESH_RELAY_URL))
        var processed = 0
        val event = NostrEvent(
            id = "7a".repeat(32),
            pubkey = "7b".repeat(32),
            createdAt = 1,
            kind = 1060,
            tags = emptyList(),
            content = "ciphertext",
            sig = "signature"
        )

        try {
            manager.subscribeAfterSuccessfulProcessing(
                filter = NostrFilter(kinds = listOf(1060)),
                id = "current-subscription",
                targetRelayUrls = listOf(FRESH_RELAY_URL)
            ) {
                processed += 1
                true
            }
            manager.connect()
            installConnection(
                manager,
                FRESH_RELAY_URL,
                RecordingWebSocket(Request.Builder().url(FRESH_RELAY_URL).build())
            )

            requireNotNull(listener).onMessage(
                originalSocket,
                """["EVENT","current-subscription",${event.toJsonString()}]"""
            )

            assertEquals(0, processed)
            assertEquals(false, deduplicator.contains(event.id))
        } finally {
            manager.disconnect()
            scope.cancel()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun replaceRelays(manager: NostrRelayManager, urls: List<String>) {
        val field = NostrRelayManager::class.java.getDeclaredField("relaysList")
        field.isAccessible = true
        val relays = field.get(manager) as MutableList<NostrRelayManager.Relay>
        synchronized(relays) {
            relays.clear()
            relays.addAll(urls.map(NostrRelayManager::Relay))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun installConnection(
        manager: NostrRelayManager,
        relayUrl: String,
        socket: WebSocket
    ) {
        val field = NostrRelayManager::class.java.getDeclaredField("connections")
        field.isAccessible = true
        val connections = field.get(manager) as MutableMap<String, WebSocket>
        connections[relayUrl] = socket
    }

    private class RecordingWebSocket(
        private val request: Request
    ) : WebSocket {
        override fun request(): Request = request
        override fun queueSize(): Long = 0L
        override fun send(text: String): Boolean = true
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() = Unit
    }

    companion object {
        private const val FRESH_RELAY_HOST = "fresh-cycle.example"
        private const val FRESH_RELAY_URL = "wss://$FRESH_RELAY_HOST/"
    }
}
