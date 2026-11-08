package com.bitchat.android.nostr

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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NostrRelayManagerPanicReconnectRobolectricTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val factory = FakeWebSocketFactory()
    private val manager = NostrRelayManager(webSocketFactory = { factory }, scope = scope)

    @After
    fun tearDown() {
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

        // OkHttp canonicalizes ws/wss request URLs to http/https.
        val RELAY_URL_PATTERN = Regex("^https://")
    }
}
