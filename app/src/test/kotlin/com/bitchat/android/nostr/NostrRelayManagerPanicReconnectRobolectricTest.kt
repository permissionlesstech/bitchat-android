package com.bitchat.android.nostr

import com.bitchat.android.geohash.LiveLocationPrivacyGate
import com.bitchat.android.util.AppConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NostrRelayManagerPanicReconnectRobolectricTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val factory = FakeWebSocketFactory()
    private val fakeMonoMs = AtomicLong(1_000L)
    private val torReady = AtomicBoolean(true)
    private val manager = NostrRelayManager(
        webSocketFactory = { factory },
        scope = scope,
        monotonicClockMs = { fakeMonoMs.get() },
        awaitTorRoute = { torReady.get() }
    )

    @After
    fun tearDown() {
        LiveLocationPrivacyGate.update(false)
        scope.cancel()
    }

    @Test
    fun `panic wipe reconnects default relays on new sockets when connectivity was desired`() {
        val oldSockets = connectAndForgetInitialSockets()

        manager.clearAllOnPanic(reconnect = true)
        dispatcher.scheduler.runCurrent()

        assertEquals(
            NostrRelayManager.defaultRelays().toSet(),
            factory.openedSinceReset.map { it.url }.toSet()
        )
        assertTrue(oldSockets.all { it.closed })
    }

    @Test
    fun `panic wipe does not reconnect relays that only the wiped session used`() {
        connectAndForgetInitialSockets()
        manager.connectToRelays(listOf(SESSION_RELAY))
        dispatcher.scheduler.runCurrent()
        assertEquals(listOf(SESSION_RELAY), factory.openedSinceReset.map { it.url })
        factory.reset()

        manager.clearAllOnPanic(reconnect = true)
        dispatcher.scheduler.runCurrent()

        assertFalse(factory.openedSinceReset.any { it.url == SESSION_RELAY })
        assertEquals(
            NostrRelayManager.defaultRelays().toSet(),
            manager.getRelayStatuses().map { it.url }.toSet()
        )
    }

    @Test
    fun `panic wipe resumes periodic subscription repair`() {
        connectAndForgetInitialSockets()
        manager.clearAllOnPanic(reconnect = true)
        dispatcher.scheduler.runCurrent()

        // The first delivery is refused, leaving the logical subscription unsent on the socket.
        factory.acceptSends = false
        manager.subscribe(
            filter = NostrFilter(kinds = listOf(NostrKind.TEXT_NOTE)),
            id = "post-panic",
            handler = {}
        )
        dispatcher.scheduler.runCurrent()
        assertFalse(anySocketSent("post-panic"))

        factory.acceptSends = true
        dispatcher.scheduler.advanceTimeBy(
            AppConstants.Nostr.SUBSCRIPTION_VALIDATION_INTERVAL_MS + 1
        )
        dispatcher.scheduler.runCurrent()

        assertTrue(anySocketSent("post-panic"))
    }

    @Test
    fun `panic wipe keeps relays offline when reconnect is withheld`() {
        connectAndForgetInitialSockets()

        manager.clearAllOnPanic(reconnect = false)
        dispatcher.scheduler.runCurrent()
        // A later relay request must not revive connectivity after a wipe that failed closed.
        manager.connectToRelays(listOf(SESSION_RELAY))
        dispatcher.scheduler.runCurrent()

        assertTrue(factory.openedSinceReset.isEmpty())
    }

    @Test
    fun `confirmed panic retry reconnects after earlier wipes withheld reconnect`() {
        connectAndForgetInitialSockets()
        manager.clearAllOnPanic(reconnect = false)
        manager.clearAllOnPanic(reconnect = false)
        dispatcher.scheduler.runCurrent()
        assertTrue(factory.openedSinceReset.isEmpty())

        manager.clearAllOnPanic(reconnect = true)
        dispatcher.scheduler.runCurrent()

        assertEquals(
            NostrRelayManager.defaultRelays().toSet(),
            factory.openedSinceReset.map { it.url }.toSet()
        )
    }

    @Test
    fun `flapping relay that opens and closes immediately escalates reconnect backoff`() {
        manager.connect()
        dispatcher.scheduler.runCurrent()

        val firstRelayUrl = NostrRelayManager.defaultRelays().first()
        val fakeResponse = okhttp3.Response.Builder()
            .request(Request.Builder().url(firstRelayUrl.replace("wss://", "https://")).build())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(101)
            .message("Switching Protocols")
            .build()

        // Simulate 4 rapid open->close flaps (never stable >= 30s)
        repeat(4) { expectedAttempt ->
            val socketAndListener = factory.openedWithListeners.last { it.first.url == firstRelayUrl }
            socketAndListener.second.onOpen(socketAndListener.first, fakeResponse)
            socketAndListener.second.onClosed(socketAndListener.first, 1000, "immediate close")
            val relay = manager.getRelayStatuses().first { it.url == firstRelayUrl }
            assertEquals(expectedAttempt + 1, relay.reconnectAttempts)
            val delayMs = RelayReconnectPolicy.backoffMs(expectedAttempt + 1)
            dispatcher.scheduler.advanceTimeBy(delayMs + 1)
            dispatcher.scheduler.runCurrent()
        }

        // Subsequent connection failures (never opening again) must continue escalating even past 30s.
        fakeMonoMs.addAndGet(60_000L)
        val socketAndListener = factory.openedWithListeners.last { it.first.url == firstRelayUrl }
        socketAndListener.second.onFailure(socketAndListener.first, RuntimeException("connect failed"), null)
        val relayAfterFail = manager.getRelayStatuses().first { it.url == firstRelayUrl }
        assertEquals(5, relayAfterFail.reconnectAttempts)
    }

    @Test
    fun `monotonic stability threshold escalates below 30s and resets at 30s`() {
        manager.connect()
        dispatcher.scheduler.runCurrent()

        val firstRelayUrl = NostrRelayManager.defaultRelays().first()
        val fakeResponse = okhttp3.Response.Builder()
            .request(Request.Builder().url(firstRelayUrl.replace("wss://", "https://")).build())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(101)
            .message("Switching Protocols")
            .build()

        // First disconnect establishes attempt = 1
        var pair = factory.openedWithListeners.last { it.first.url == firstRelayUrl }
        pair.second.onOpen(pair.first, fakeResponse)
        fakeMonoMs.addAndGet(1_000L)
        pair.second.onClosed(pair.first, 1000, "flap 1")
        assertEquals(1, manager.getRelayStatuses().first { it.url == firstRelayUrl }.reconnectAttempts)
        dispatcher.scheduler.advanceTimeBy(RelayReconnectPolicy.backoffMs(1) + 1)
        dispatcher.scheduler.runCurrent()

        // 29,999ms open -> still below stability threshold -> escalates to attempt 2
        pair = factory.openedWithListeners.last { it.first.url == firstRelayUrl }
        pair.second.onOpen(pair.first, fakeResponse)
        fakeMonoMs.addAndGet(29_999L)
        pair.second.onClosed(pair.first, 1000, "below threshold")
        assertEquals(2, manager.getRelayStatuses().first { it.url == firstRelayUrl }.reconnectAttempts)
        dispatcher.scheduler.advanceTimeBy(RelayReconnectPolicy.backoffMs(2) + 1)
        dispatcher.scheduler.runCurrent()

        // Exactly 30,000ms open -> stable -> resets previous attempts to 0, so next attempt is 1
        pair = factory.openedWithListeners.last { it.first.url == firstRelayUrl }
        pair.second.onOpen(pair.first, fakeResponse)
        fakeMonoMs.addAndGet(30_000L)
        pair.second.onClosed(pair.first, 1000, "stable then closed")
        assertEquals(1, manager.getRelayStatuses().first { it.url == firstRelayUrl }.reconnectAttempts)
    }

    @Test
    fun `network recovery does not reopen a revoked live-location relay`() {
        connectAndForgetInitialSockets()
        LiveLocationPrivacyGate.update(true)
        val liveToken = LiveLocationPrivacyGate.captureToken()!!

        // Inject a synthetic live-location-only relay into relaysList + liveLocationRelayTokens
        val liveOnlyUrl = "wss://live-only-geo.example"
        val liveTokensField = NostrRelayManager::class.java.getDeclaredField("liveLocationRelayTokens")
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (liveTokensField.get(manager) as MutableMap<String, Long>)[liveOnlyUrl] = liveToken

        val relaysField = NostrRelayManager::class.java.getDeclaredField("relaysList")
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val relaysList = relaysField.get(manager) as MutableList<NostrRelayManager.Relay>
        synchronized(relaysList) {
            relaysList.add(NostrRelayManager.Relay(liveOnlyUrl))
        }

        // Revoke live location -> removes liveOnlyUrl and clears liveLocationRelayTokens
        LiveLocationPrivacyGate.update(false)
        // Even if a stale entry remained in a reconnect snapshot, missing token must fail closed
        synchronized(relaysList) {
            relaysList.add(NostrRelayManager.Relay(liveOnlyUrl))
        }
        factory.reset()

        manager.reconnectDisconnectedRelays(resetBackoff = true)
        dispatcher.scheduler.runCurrent()

        assertFalse(
            "revoked live-location relay must never be reopened on network recovery",
            factory.openedSinceReset.any { it.url == liveOnlyUrl }
        )
    }

    @Test
    fun `slow Tor route timeout schedules backoff retry and recovers once Tor is ready`() {
        val routeChecks = AtomicInteger(0)
        val slowTorManager = NostrRelayManager(
            webSocketFactory = { factory },
            scope = scope,
            monotonicClockMs = { fakeMonoMs.get() },
            awaitTorRoute = {
                // Fail first wave (4 default relays), then succeed once Tor recovers
                routeChecks.incrementAndGet() > NostrRelayManager.defaultRelays().size
            }
        )

        slowTorManager.connect()
        dispatcher.scheduler.runCurrent()
        assertTrue("no sockets should open while Tor route timed out", factory.openedSinceReset.isEmpty())

        // Backoff retry should fire and connect all default relays once Tor route is ready
        dispatcher.scheduler.advanceTimeBy(RelayReconnectPolicy.backoffMs(1) + 1)
        dispatcher.scheduler.runCurrent()

        assertEquals(
            NostrRelayManager.defaultRelays().toSet(),
            factory.openedSinceReset.map { it.url }.toSet()
        )
    }

    @Test
    fun `panic wipe leaves a manager that was never connected offline`() {
        manager.clearAllOnPanic(reconnect = true)
        dispatcher.scheduler.runCurrent()

        assertTrue(factory.openedSinceReset.isEmpty())
    }

    private fun connectAndForgetInitialSockets(): List<FakeWebSocket> {
        manager.connect()
        dispatcher.scheduler.runCurrent()
        val initialSockets = factory.openedSinceReset.toList()
        assertEquals(NostrRelayManager.defaultRelays().size, initialSockets.size)
        factory.reset()
        return initialSockets
    }

    private fun anySocketSent(subscriptionId: String): Boolean =
        factory.openedSinceReset.any { socket ->
            socket.sent.any { it.contains("\"$subscriptionId\"") }
        }

    private class FakeWebSocketFactory : WebSocket.Factory {
        val openedSinceReset = mutableListOf<FakeWebSocket>()
        val openedWithListeners = mutableListOf<Pair<FakeWebSocket, WebSocketListener>>()

        @Volatile
        var acceptSends = true

        fun reset() {
            openedSinceReset.clear()
            openedWithListeners.clear()
        }

        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
            FakeWebSocket(request, this).also { ws ->
                openedSinceReset.add(ws)
                openedWithListeners.add(ws to listener)
            }
    }

    private class FakeWebSocket(
        private val originalRequest: Request,
        private val factory: FakeWebSocketFactory
    ) : WebSocket {
        val url: String = RELAY_URL_PATTERN.replace(originalRequest.url.toString(), "wss://")
            .trimEnd('/')
        val sent = mutableListOf<String>()
        var closed = false

        override fun request(): Request = originalRequest
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean {
            if (!factory.acceptSends) return false
            sent += text
            return true
        }
        override fun send(bytes: ByteString): Boolean = factory.acceptSends
        override fun close(code: Int, reason: String?): Boolean {
            closed = true
            return true
        }
        override fun cancel() {
            closed = true
        }
    }

    private companion object {
        const val SESSION_RELAY = "wss://session-relay.example"

        // OkHttp canonicalizes ws/wss request URLs to http/https.
        val RELAY_URL_PATTERN = Regex("^https://")
    }
}
