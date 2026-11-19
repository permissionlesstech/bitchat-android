package com.bitchat.android.nostr

import kotlinx.coroutines.flow.emptyFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The relay manager opens its sockets through exactly the clients it was built with. There is no
 * other source of clients to fall back on: the app binds them, and without that binding nothing
 * that needs them compiles.
 */
@RunWith(RobolectricTestRunner::class)
class NostrRelayManagerClientsTest {

    @Test
    fun `every relay socket opens through the constructed clients`() {
        val opened = LinkedBlockingQueue<Request>()
        // Records sockets instead of opening them, so the test never reaches a relay.
        val webSocketClient = object : OkHttpClient() {
            override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
                mock<WebSocket>().also { opened.add(request) }
        }
        val manager = newRelayManager(
            clients = object : NostrHttpClients {
                override fun httpClient() = error("not called")
                override fun webSocketClient() = webSocketClient
                override val routeChanges = emptyFlow<Unit>()
            }
        )
        val relayCount = manager.getRelayStatuses().size

        manager.connect()

        val sockets = List(relayCount) { opened.poll(5, TimeUnit.SECONDS) }
        assertEquals("a relay opened no socket through the clients", relayCount, sockets.filterNotNull().size)
        assertNull("opened more sockets than relays", opened.poll(100, TimeUnit.MILLISECONDS))
    }
}
