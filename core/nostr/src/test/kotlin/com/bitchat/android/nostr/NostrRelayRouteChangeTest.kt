package com.bitchat.android.nostr

import kotlinx.coroutines.flow.MutableSharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
class NostrRelayRouteChangeTest {

    private val routeChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val opened = LinkedBlockingQueue<RecordingWebSocket>()
    private val failNextClose = AtomicBoolean(false)

    // Records sockets instead of opening them, so the test never reaches a relay.
    private val webSocketClient = object : OkHttpClient() {
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
            RecordingWebSocket(request, failNextClose).also(opened::add)
    }

    private val manager = NostrRelayManager.shared

    @After
    fun tearDown() {
        manager.disconnect()
        NostrNetwork.uninstallForTesting()
    }

    private fun installClients() {
        NostrNetwork.install(object : NostrHttpClients {
            override fun httpClient() = error("not called")
            override fun webSocketClient() = this@NostrRelayRouteChangeTest.webSocketClient
            override val routeChanges = this@NostrRelayRouteChangeTest.routeChanges
        })
    }

    @Test
    fun `a reset that throws does not stop the next route change from resetting`() {
        installClients()
        manager.disconnect()
        val relayCount = manager.getRelayStatuses().size

        manager.connect()
        val firstSockets = takeSockets(relayCount)
        failNextClose.set(true)
        assertTrue(routeChanges.tryEmit(Unit))
        assertTrue(
            "the failing reset never reached a socket",
            firstSockets.any { it.closeAttempted.await(5, TimeUnit.SECONDS) }
        )
        opened.clear()

        manager.connect()
        val secondSockets = takeSockets(relayCount)
        assertTrue(routeChanges.tryEmit(Unit))

        secondSockets.forEach { socket ->
            assertTrue(
                "socket to ${socket.request().url} survived the route change after a failed reset",
                socket.closed.await(5, TimeUnit.SECONDS)
            )
        }
    }

    @Test
    fun `a route change closes every relay socket opened on the old route and reconnects`() {
        installClients()
        manager.disconnect()
        val relayCount = manager.getRelayStatuses().size
        assertTrue(relayCount > 0)

        manager.connect()
        val oldRouteSockets = takeSockets(relayCount)

        assertTrue(routeChanges.tryEmit(Unit))

        oldRouteSockets.forEach { socket ->
            assertTrue(
                "socket to ${socket.request().url} survived the route change",
                socket.closed.await(5, TimeUnit.SECONDS)
            )
        }
        val newRouteSockets = takeSockets(relayCount)
        assertEquals(
            oldRouteSockets.map { it.request().url }.toSet(),
            newRouteSockets.map { it.request().url }.toSet()
        )
    }

    private fun takeSockets(count: Int): List<RecordingWebSocket> = List(count) {
        val socket = opened.poll(5, TimeUnit.SECONDS)
        assertNotNull("expected $count relay sockets", socket)
        socket!!
    }

    private class RecordingWebSocket(
        private val request: Request,
        private val failNextClose: AtomicBoolean
    ) : WebSocket {
        val closeAttempted = CountDownLatch(1)
        val closed = CountDownLatch(1)
        override fun request() = request
        override fun queueSize() = 0L
        override fun send(text: String) = true
        override fun send(bytes: ByteString) = true
        override fun close(code: Int, reason: String?): Boolean {
            closeAttempted.countDown()
            if (failNextClose.getAndSet(false)) throw IllegalStateException("close failed")
            closed.countDown()
            return true
        }
        override fun cancel() = closed.countDown()
    }
}
