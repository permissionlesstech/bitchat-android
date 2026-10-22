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

@RunWith(RobolectricTestRunner::class)
class NostrRelayRouteChangeTest {

    private val routeChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val opened = LinkedBlockingQueue<RecordingWebSocket>()

    // Records sockets instead of opening them, so the test never reaches a relay.
    private val webSocketClient = object : OkHttpClient() {
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
            RecordingWebSocket(request).also(opened::add)
    }

    private val manager = NostrRelayManager.shared

    @After
    fun tearDown() {
        manager.disconnect()
        NostrNetwork.uninstallForTesting()
    }

    @Test
    fun `a route change closes every relay socket opened on the old route and reconnects`() {
        NostrNetwork.install(object : NostrHttpClients {
            override fun httpClient() = error("not called")
            override fun webSocketClient() = this@NostrRelayRouteChangeTest.webSocketClient
            override val routeChanges = this@NostrRelayRouteChangeTest.routeChanges
        })
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

    private class RecordingWebSocket(private val request: Request) : WebSocket {
        val closed = CountDownLatch(1)
        override fun request() = request
        override fun queueSize() = 0L
        override fun send(text: String) = true
        override fun send(bytes: ByteString) = true
        override fun close(code: Int, reason: String?): Boolean {
            closed.countDown()
            return true
        }
        override fun cancel() = closed.countDown()
    }
}
