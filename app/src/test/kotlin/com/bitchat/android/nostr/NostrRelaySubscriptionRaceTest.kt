package com.bitchat.android.nostr

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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

    @Test
    fun ndrAdapterNeverUsesConnectedLiveLocationOnlyRelay() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val manager = NostrRelayManager(
            scope,
            NostrEventDeduplicator(maxCapacity = 8)
        )
        val accountRelayUrl = NostrRelayManager.defaultRelays().first()
        val liveOnlyRelayUrl = "wss://live-location-only.example"
        val accountSocket = RecordingWebSocket()
        val liveOnlySocket = RecordingWebSocket()
        installConnection(manager, accountRelayUrl, accountSocket)
        installConnection(manager, liveOnlyRelayUrl, liveOnlySocket)
        val adapter = BitchatNdrRelayAdapter(manager)

        adapter.subscribe(
            filter = NostrFilter(kinds = listOf(1060)),
            id = "ndr-account-only"
        ) { true }

        val event = NostrEvent(
            id = "7c".repeat(32),
            pubkey = "7d".repeat(32),
            createdAt = 1,
            kind = 1060,
            tags = emptyList(),
            content = "ciphertext",
            sig = "signature"
        )
        var accepted: Boolean? = null
        adapter.sendEventConfirmed(event) { accepted = it }

        assertTrue(accountSocket.messages.any { "\"REQ\"" in it })
        assertTrue(accountSocket.messages.any { "\"EVENT\"" in it })
        assertTrue(liveOnlySocket.messages.isEmpty())
        assertEquals(null, accepted)

        adapter.cancelConfirmedEvent(event.id)
        assertFalse(accepted ?: true)
    }

    @Test
    fun ndrAdapterRejectsSubscriptionDuringAccountReset() {
        val manager = NostrRelayManager(
            CoroutineScope(Dispatchers.Unconfined + SupervisorJob()),
            NostrEventDeduplicator(maxCapacity = 8)
        )
        val adapter = BitchatNdrRelayAdapter(manager)
        val resetToken = manager.beginAccountReset()

        assertThrows(IllegalStateException::class.java) {
            adapter.subscribe(
                filter = NostrFilter(kinds = listOf(1060)),
                id = "blocked-ndr"
            ) { true }
        }

        assertTrue(manager.discardForAccountReset(resetToken))
        assertTrue(manager.completeAccountReset(resetToken))
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
            String::class.java,
            Long::class.javaPrimitiveType
        )
        method.isAccessible = true
        method.invoke(
            manager,
            """["EVENT","$subscriptionId",${event.toJsonString()}]""",
            RELAY_URL,
            manager.captureAccountGeneration()
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

    private class RecordingWebSocket : WebSocket {
        val messages = mutableListOf<String>()

        override fun request(): Request =
            Request.Builder().url("https://relay.example").build()

        override fun queueSize(): Long = 0L

        override fun send(text: String): Boolean {
            messages += text
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
