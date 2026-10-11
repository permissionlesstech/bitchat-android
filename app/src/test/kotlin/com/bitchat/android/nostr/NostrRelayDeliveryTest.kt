package com.bitchat.android.nostr

import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A loopback-only relay: no public services, persisted accounts, or timing sleeps. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class NostrRelayDeliveryTest {
    @Test fun `socket acceptance is not delivery and relay rejection stays unsuccessful`() = runBlocking {
        exerciseRelay(accepted = false)
    }

    @Test fun `publication completes only after the selected relay accepts it`() = runBlocking {
        exerciseRelay(accepted = true)
    }

    private suspend fun exerciseRelay(accepted: Boolean) = coroutineScope {
        val received = CompletableDeferred<Pair<WebSocket, String>>()
        val server = MockWebServer()
        server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = JsonParser.parseString(text).asJsonArray
                if (frame[0].asString == "EVENT") received.complete(webSocket to frame[1].asJsonObject["id"].asString)
            }
        }).build())
        server.start()
        val url = server.url("/").toString().replace("http://", "ws://")
        val client = OkHttpClient()
        val manager = NostrRelayManager(listOf(url)) { client }
        try {
            manager.connect()
            withTimeout(5_000) { manager.isConnected.first { it } }
            val event = signedEvent()
            val result = async { manager.publishConfirmed(event, listOf(url)) }
            val (socket, id) = withTimeout(5_000) { received.await() }
            assertEquals(event.id, id)
            assertFalse("WebSocket send must not mark the message sent", result.isCompleted)
            socket.send("[\"OK\",\"$id\",$accepted,\"fixture\"]")
            assertEquals(accepted, withTimeout(5_000) { result.await() })
        } finally {
            manager.clearAllSubscriptions()
            manager.disconnect()
            server.close()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun signedEvent(): NostrEvent {
        val key = "0".repeat(63) + "1"
        return NostrEvent(pubkey = NostrCrypto.derivePublicKey(key), createdAt = 1,
            kind = NostrKind.TEXT_NOTE, tags = emptyList(), content = "synthetic").sign(key)
    }
}
