package com.bitchat.android.nostr

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
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
    private val manager = newRelayManager(
        clients = object : NostrHttpClients {
            override fun httpClient() = error("not called")
            override fun webSocketClient(): OkHttpClient = factory
            override val routeChanges = this@NostrRelayManagerPanicReconnectRobolectricTest.routeChanges
        },
        dispatcher = dispatcher,
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

        @Volatile
        var acceptSends = true

        fun reset() {
            openedSinceReset.clear()
        }

        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
            FakeWebSocket(request, this).also(openedSinceReset::add)
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
