package com.bitchat.android.nostr

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NostrRelayBackgroundRecoveryRobolectricTest {

    private val dispatcher = StandardTestDispatcher()
    private val fakeMonoMs = AtomicLong(10_000L)
    private val isBackground = MutableStateFlow(true)
    private val factory = FakeWebSocketFactory()

    @Test
    fun `background-throttled relay wakes immediately when healthy alternative disconnects`() {
        val manager = newManager()
        val relays = NostrRelayManager.defaultRelays()
        val deadRelay = relays[0]
        val healthyRelays = relays.drop(1)

        manager.connect()
        dispatcher.scheduler.runCurrent()

        val healthyPairs = healthyRelays.map { url -> openRelay(url) }
        failRelayIntoBackgroundThrottle(deadRelay)

        // Confirm deadRelay is throttled past normal attempt-4 backoff while healthy relays are up
        factory.reset()
        advance(RelayReconnectPolicy.backoffMs(4) + 1L)
        assertFalse(
            "deadRelay should stay throttled while healthy alternatives are connected",
            factory.openedSinceReset.any { it.url == deadRelay }
        )

        // Drop all healthy alternatives -> deadRelay must wake immediately without waiting 15m
        factory.reset()
        healthyPairs.forEach { (ws, listener) ->
            listener.onFailure(ws, IOException("healthy relay lost"), null)
        }
        dispatcher.scheduler.runCurrent()

        assertTrue(
            "throttled relay must reconnect immediately when healthy alternatives disappear",
            factory.openedSinceReset.any { it.url == deadRelay }
        )
    }

    @Test
    fun `background-throttled relay wakes immediately when a new subscription targets it exclusively`() {
        val manager = newManager()
        val relays = NostrRelayManager.defaultRelays()
        val deadRelay = relays[0]
        val healthyRelay = relays[1]

        manager.connect()
        dispatcher.scheduler.runCurrent()

        openRelay(healthyRelay)
        failRelayIntoBackgroundThrottle(deadRelay)

        factory.reset()
        advance(RelayReconnectPolicy.backoffMs(4) + 1L)
        assertFalse(
            "deadRelay should stay throttled before sole-target subscription arrives",
            factory.openedSinceReset.any { it.url == deadRelay }
        )

        // Add a new subscription targeting ONLY deadRelay -> must wake immediately without waiting 15m
        factory.reset()
        manager.subscribe(
            filter = NostrFilter(kinds = listOf(NostrKind.TEXT_NOTE)),
            id = "sole-target-sub",
            handler = {},
            targetRelayUrls = listOf(deadRelay)
        )
        dispatcher.scheduler.runCurrent()

        assertTrue(
            "throttled relay must reconnect immediately when a subscription targets it exclusively",
            factory.openedSinceReset.any { it.url == deadRelay }
        )
    }

    @Test
    fun `background-throttled relay wakes immediately when healthy extra relay is released`() {
        val manager = newManager()
        val relays = NostrRelayManager.defaultRelays()
        val deadRelay = relays[0]
        val extraHealthyRelay = "wss://extra-healthy.example"

        manager.connect()
        dispatcher.scheduler.runCurrent()
        manager.connectToRelays(listOf(extraHealthyRelay))
        dispatcher.scheduler.runCurrent()

        // Only the extra relay is connected; all default relays fail
        openRelay(extraHealthyRelay)
        failRelayIntoBackgroundThrottle(deadRelay)

        factory.reset()
        advance(RelayReconnectPolicy.backoffMs(4) + 1L)
        assertFalse(factory.openedSinceReset.any { it.url == deadRelay })

        // Releasing the sole connected extra relay must wake throttled default relays immediately
        factory.reset()
        manager.releaseRelays(listOf(extraHealthyRelay))
        dispatcher.scheduler.runCurrent()

        assertTrue(
            "releasing the last healthy alternative must wake throttled relays immediately",
            factory.openedSinceReset.any { it.url == deadRelay }
        )
    }

    @Test
    fun `getRelayStatuses reports live sent and received counters without waiting for reconnect`() {
        val manager = newManager()
        val targetRelay = NostrRelayManager.defaultRelays()[0]
        manager.connect()
        dispatcher.scheduler.runCurrent()

        val (ws, listener) = openRelay(targetRelay)
        val identity = NostrIdentity.fromPrivateKey("33".repeat(32))
        val event = NostrEvent(
            pubkey = identity.publicKeyHex,
            createdAt = 1_700_000_000,
            kind = NostrKind.TEXT_NOTE,
            tags = emptyList(),
            content = "live-counter-check"
        ).sign(identity.privateKeyHex)

        manager.sendEvent(event, relayUrls = listOf(targetRelay))
        dispatcher.scheduler.runCurrent()

        val inboundJson = """["EVENT","sub-1",{"id":"${event.id}","pubkey":"${event.pubkey}","created_at":${event.createdAt},"kind":${event.kind},"tags":[],"content":"live-counter-check","sig":"${event.sig}"}]"""
        listener.onMessage(ws, inboundJson)

        val liveStatus = manager.getRelayStatuses().first { it.url == targetRelay }
        assertEquals(1, liveStatus.messagesSent)
        assertEquals(1, liveStatus.messagesReceived)
    }

    private fun newManager() = newRelayManager(
        clients = object : NostrHttpClients {
            override fun httpClient() = error("not called")
            override fun webSocketClient(): OkHttpClient = factory
            override val routeChanges = emptyFlow<Unit>()
            override suspend fun awaitRoute(timeoutMs: Long) = true
        },
        dispatcher = dispatcher,
        monotonicClockMs = { fakeMonoMs.get() },
        backgroundState = isBackground,
    )

    private fun openRelay(url: String): Pair<FakeWebSocket, WebSocketListener> {
        val openResponse = Response.Builder()
            .request(Request.Builder().url("https://relay.example").build())
            .protocol(Protocol.HTTP_1_1)
            .code(101)
            .message("Switching Protocols")
            .build()
        return factory.openedWithListeners.last { it.first.url == url }.also { (ws, listener) ->
            listener.onOpen(ws, openResponse)
        }
    }

    private fun failRelayIntoBackgroundThrottle(relayUrl: String) {
        repeat(4) { attempt ->
            val (ws, listener) = factory.openedWithListeners.last { it.first.url == relayUrl }
            listener.onFailure(ws, IOException("scripted connect failure"), null)
            if (attempt < 3) {
                advance(RelayReconnectPolicy.backoffMs(attempt + 1) + 1L)
            }
        }
    }

    private fun advance(ms: Long) {
        dispatcher.scheduler.advanceTimeBy(ms)
        fakeMonoMs.addAndGet(ms)
        dispatcher.scheduler.runCurrent()
    }

    // Records sockets instead of opening them, so the test never reaches a relay.
    private class FakeWebSocketFactory : OkHttpClient() {
        val openedSinceReset = mutableListOf<FakeWebSocket>()
        val openedWithListeners = mutableListOf<Pair<FakeWebSocket, WebSocketListener>>()

        fun reset() {
            openedSinceReset.clear()
            openedWithListeners.clear()
        }

        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
            FakeWebSocket(request).also { ws ->
                openedSinceReset.add(ws)
                openedWithListeners.add(ws to listener)
            }
    }

    private class FakeWebSocket(
        private val originalRequest: Request
    ) : WebSocket {
        val url: String = RELAY_URL_PATTERN.replace(originalRequest.url.toString(), "wss://").trimEnd('/')

        override fun request(): Request = originalRequest
        override fun queueSize(): Long = 0L
        override fun send(text: String): Boolean = true
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() = Unit
    }

    private companion object {
        val RELAY_URL_PATTERN = Regex("^https://")
    }
}
