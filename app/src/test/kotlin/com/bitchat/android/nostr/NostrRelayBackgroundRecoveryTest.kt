package com.bitchat.android.nostr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NostrRelayBackgroundRecoveryTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val fakeMonoMs = AtomicLong(10_000L)
    private val isBackground = MutableStateFlow(true)
    private val factory = FakeWebSocketFactory()

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `background-throttled relay wakes immediately when healthy alternative disconnects`() {
        val manager = NostrRelayManager(
            webSocketFactory = { factory },
            scope = scope,
            monotonicClockMs = { fakeMonoMs.get() },
            awaitTorRoute = { true },
            backgroundStateFlow = isBackground
        )
        val relays = NostrRelayManager.defaultRelays()
        val deadRelay = relays[0]
        val healthyRelays = relays.drop(1)

        manager.connect()
        dispatcher.scheduler.runCurrent()

        // Mark all other relays open and healthy
        val openResponse = Response.Builder()
            .request(Request.Builder().url("https://relay.example").build())
            .protocol(Protocol.HTTP_1_1)
            .code(101)
            .message("Switching Protocols")
            .build()
        val healthyPairs = healthyRelays.map { url ->
            factory.openedWithListeners.last { it.first.url == url }.also { (ws, listener) ->
                listener.onOpen(ws, openResponse)
            }
        }

        // Fail deadRelay 4 times in background so it enters the 15-minute backoff
        repeat(4) { attempt ->
            val (ws, listener) = factory.openedWithListeners.last { it.first.url == deadRelay }
            listener.onFailure(ws, IOException("scripted connect failure"), null)
            if (attempt < 3) {
                val backoff = RelayReconnectPolicy.backoffMs(attempt + 1) + 1L
                dispatcher.scheduler.advanceTimeBy(backoff)
                fakeMonoMs.addAndGet(backoff)
                dispatcher.scheduler.runCurrent()
            }
        }

        // Confirm deadRelay is throttled past normal attempt-4 backoff while healthy relays are up
        factory.reset()
        val normalBackoff = RelayReconnectPolicy.backoffMs(4) + 1L
        dispatcher.scheduler.advanceTimeBy(normalBackoff)
        fakeMonoMs.addAndGet(normalBackoff)
        dispatcher.scheduler.runCurrent()
        assertFalse(
            "deadRelay should stay throttled while healthy alternatives are connected",
            factory.openedSinceReset.any { it.url == deadRelay }
        )

        // Now drop all healthy alternatives -> deadRelay must wake immediately without waiting 15m
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
        val manager = NostrRelayManager(
            webSocketFactory = { factory },
            scope = scope,
            monotonicClockMs = { fakeMonoMs.get() },
            awaitTorRoute = { true },
            backgroundStateFlow = isBackground
        )
        val relays = NostrRelayManager.defaultRelays()
        val deadRelay = relays[0]
        val healthyRelay = relays[1]

        manager.connect()
        dispatcher.scheduler.runCurrent()

        val openResponse = Response.Builder()
            .request(Request.Builder().url("https://relay.example").build())
            .protocol(Protocol.HTTP_1_1)
            .code(101)
            .message("Switching Protocols")
            .build()
        factory.openedWithListeners.last { it.first.url == healthyRelay }.let { (ws, listener) ->
            listener.onOpen(ws, openResponse)
        }

        // Fail deadRelay 4 times in background so it enters the 15-minute backoff
        repeat(4) { attempt ->
            val (ws, listener) = factory.openedWithListeners.last { it.first.url == deadRelay }
            listener.onFailure(ws, IOException("scripted connect failure"), null)
            if (attempt < 3) {
                val backoff = RelayReconnectPolicy.backoffMs(attempt + 1) + 1L
                dispatcher.scheduler.advanceTimeBy(backoff)
                fakeMonoMs.addAndGet(backoff)
                dispatcher.scheduler.runCurrent()
            }
        }

        // Confirm deadRelay is throttled past normal attempt-4 backoff
        factory.reset()
        val normalBackoff = RelayReconnectPolicy.backoffMs(4) + 1L
        dispatcher.scheduler.advanceTimeBy(normalBackoff)
        fakeMonoMs.addAndGet(normalBackoff)
        dispatcher.scheduler.runCurrent()
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

    private class FakeWebSocketFactory : WebSocket.Factory {
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
