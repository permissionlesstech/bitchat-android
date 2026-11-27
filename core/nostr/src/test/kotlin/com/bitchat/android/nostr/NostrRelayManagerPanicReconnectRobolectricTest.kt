package com.bitchat.android.nostr

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Upstream's panic reconnect (1e3515dd, c1b2e1be): relays must not come back before the wipe is
 * confirmed, and must come back afterwards on the default relays only.
 *
 * On this branch the panic's release ([NostrRelayManager.clearAllOnPanic]) always leaves the manager
 * offline, and the reconnect is the next identity's start-up calling [NostrRelayManager.connect],
 * which PanicClear runs only after the wipe confirmed (PanicClearTest pins that order). So the
 * reconnect cases call [NostrRelayManager.connect] where upstream passed `reconnect = true`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NostrRelayManagerPanicReconnectRobolectricTest {
    private val dispatcher = StandardTestDispatcher()
    private val routeChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val factory = FakeWebSocketFactory()
    private val fakeMonoMs = AtomicLong(1_000L)
    private val torReady = AtomicBoolean(true)
    private val clients = object : NostrHttpClients {
        override fun httpClient() = error("not called")
        override fun webSocketClient(): OkHttpClient = factory
        override val routeChanges = this@NostrRelayManagerPanicReconnectRobolectricTest.routeChanges
        override fun routeGeneration() = 0L
        override suspend fun awaitRoute(timeoutMs: Long) = torReady.get()
    }
    private val manager = newRelayManager(
        clients = clients,
        dispatcher = dispatcher,
        monotonicClockMs = { fakeMonoMs.get() },
    )

    @Test
    fun `panic release closes every relay socket and opens none`() {
        val oldSockets = connectAndForgetInitialSockets()

        manager.clearAllOnPanic()
        dispatcher.scheduler.runCurrent()

        assertTrue(oldSockets.all { it.closed })
        assertTrue(factory.openedSinceReset.isEmpty())
    }

    @Test
    fun `nothing during the wipe brings relays back`() {
        connectAndForgetInitialSockets()

        manager.clearAllOnPanic()
        dispatcher.scheduler.runCurrent()
        // A relay request or subscription from the ending session, and a route change.
        manager.connectToRelays(listOf(SESSION_RELAY))
        manager.subscribe(
            filter = NostrFilter(kinds = listOf(NostrKind.TEXT_NOTE)),
            id = "late",
            handler = {}
        )
        assertTrue(routeChanges.tryEmit(Unit))
        dispatcher.scheduler.advanceTimeBy(60_000)
        dispatcher.scheduler.runCurrent()

        assertTrue(factory.openedSinceReset.isEmpty())
    }

    @Test
    fun `the next identity's connect reopens the default relays on new sockets`() {
        val oldSockets = connectAndForgetInitialSockets()
        manager.clearAllOnPanic()
        dispatcher.scheduler.runCurrent()

        manager.connect()
        dispatcher.scheduler.runCurrent()

        assertEquals(
            NostrRelayManager.defaultRelays().toSet(),
            factory.openedSinceReset.map { it.url }.toSet()
        )
        assertTrue(factory.openedSinceReset.none { it in oldSockets })
    }

    @Test
    fun `relays that only the wiped session used are forgotten`() {
        connectAndForgetInitialSockets()
        manager.connectToRelays(listOf(SESSION_RELAY))
        dispatcher.scheduler.runCurrent()
        assertEquals(listOf(SESSION_RELAY), factory.openedSinceReset.map { it.url })
        factory.reset()

        manager.clearAllOnPanic()
        manager.connect()
        dispatcher.scheduler.runCurrent()

        assertFalse(factory.openedSinceReset.any { it.url == SESSION_RELAY })
        assertEquals(
            NostrRelayManager.defaultRelays().toSet(),
            manager.getRelayStatuses().map { it.url }.toSet()
        )
    }

    @Test
    fun `reconnecting after a panic resumes periodic subscription repair`() {
        connectAndForgetInitialSockets()
        manager.clearAllOnPanic()
        manager.connect()
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
        dispatcher.scheduler.advanceTimeBy(VALIDATION_WAIT_MS)
        dispatcher.scheduler.runCurrent()

        assertTrue(anySocketSent("post-panic"))
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
        val gate = manager.liveLocationGate
        gate.update(true)
        val liveToken = gate.captureToken()!!

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
        gate.update(false)
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
        val slowTorManager = newRelayManager(
            clients = object : NostrHttpClients by clients {
                // Fail first wave (4 default relays), then succeed once Tor recovers
                override suspend fun awaitRoute(timeoutMs: Long) =
                    routeChecks.incrementAndGet() > NostrRelayManager.defaultRelays().size
            },
            dispatcher = dispatcher,
            monotonicClockMs = { fakeMonoMs.get() },
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
    fun `background dead-relay throttle does not defer a subscription sole target and foregrounding recovers throttled relays`() {
        val isBackground = MutableStateFlow(true)
        val bgManager = newRelayManager(
            clients = clients,
            dispatcher = dispatcher,
            monotonicClockMs = { fakeMonoMs.get() },
            backgroundState = isBackground,
        )
        bgManager.connect()
        dispatcher.scheduler.runCurrent()

        val defaultRelays = NostrRelayManager.defaultRelays()
        val healthyUrl = defaultRelays[0]
        val deadDefaultUrl = defaultRelays[1]
        val soleTargetUrl = "wss://sole-target.example"

        // Mark one default relay healthy so _isConnected == true
        val okResponse = okhttp3.Response.Builder()
            .request(Request.Builder().url(healthyUrl.replace("wss://", "https://")).build())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(101)
            .message("Switching Protocols")
            .build()
        val healthyPair = factory.openedWithListeners.last { it.first.url == healthyUrl }
        val relaysBeforeOpen = bgManager.relays.value
        healthyPair.second.onOpen(healthyPair.first, okResponse)
        // Verify immutable snapshot emission (R2-SD5): new status list differs from previous snapshot
        assertFalse(relaysBeforeOpen.first { it.url == healthyUrl }.isConnected)
        assertTrue(bgManager.relays.value.first { it.url == healthyUrl }.isConnected)

        // Connect soleTargetUrl and subscribe exclusively to it
        bgManager.connectToRelays(listOf(soleTargetUrl))
        dispatcher.scheduler.runCurrent()
        bgManager.subscribe(
            filter = NostrFilter(kinds = listOf(NostrKind.TEXT_NOTE)),
            id = "sole-sub",
            handler = {},
            targetRelayUrls = listOf(soleTargetUrl)
        )

        // Fail both soleTargetUrl and deadDefaultUrl 4 times while backgrounded
        repeat(4) { attempt ->
            val solePair = factory.openedWithListeners.last { it.first.url == soleTargetUrl }
            solePair.second.onFailure(solePair.first, RuntimeException("fail"), null)
            val deadPair = factory.openedWithListeners.last { it.first.url == deadDefaultUrl }
            deadPair.second.onFailure(deadPair.first, RuntimeException("fail"), null)

            if (attempt < 3) {
                dispatcher.scheduler.advanceTimeBy(RelayReconnectPolicy.backoffMs(attempt + 1) + 1)
                dispatcher.scheduler.runCurrent()
            }
        }

        // At attempt 4: soleTargetUrl MUST NOT be throttled to 15m (normal backoff is 8s),
        // whereas deadDefaultUrl (which has healthyUrl serving general subscriptions) IS throttled to 15m!
        factory.reset()
        dispatcher.scheduler.advanceTimeBy(RelayReconnectPolicy.backoffMs(4) + 1)
        dispatcher.scheduler.runCurrent()
        assertTrue(
            "sole subscription target must reconnect on normal backoff even in background",
            factory.openedSinceReset.any { it.url == soleTargetUrl }
        )
        assertFalse(
            "unrelated dead default relay should remain throttled in background",
            factory.openedSinceReset.any { it.url == deadDefaultUrl }
        )

        // Returning to foreground immediately recovers the background-throttled relay
        factory.reset()
        isBackground.value = false
        dispatcher.scheduler.runCurrent()
        assertTrue(
            "foregrounding must immediately reconnect background-throttled relays",
            factory.openedSinceReset.any { it.url == deadDefaultUrl }
        )
    }

    @Test
    fun `panic release leaves a manager that was never connected offline`() {
        manager.clearAllOnPanic()
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

    // Records sockets instead of opening them, so the test never reaches a relay.
    private class FakeWebSocketFactory : OkHttpClient() {
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

        // Longer than any power profile's subscription validation interval.
        const val VALIDATION_WAIT_MS = 10 * 60_000L

        // OkHttp canonicalizes ws/wss request URLs to http/https.
        val RELAY_URL_PATTERN = Regex("^https://")
    }
}
