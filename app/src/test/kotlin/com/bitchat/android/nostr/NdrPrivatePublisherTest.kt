package com.bitchat.android.nostr

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NdrPrivatePublisherTest {
    @Test
    fun authRejectionRetriesOnFreshSendOnlySocket() = runTest {
        val sockets = mutableListOf<Socket>()
        val publisher = NdrPrivatePublisher(this) { request, listener ->
            Socket(request, listener).also(sockets::add)
        }
        val event = event()
        val results = mutableListOf<Boolean>()
        for (rejection in listOf("[\"AUTH\",\"challenge\"]", "[\"OK\",\"${event.id}\",false,\"auth-required: login\"]")) {
            publisher.send(event, listOf(URL), { true }, results::add)
            val socket = sockets.last()
            socket.open()
            assertEquals(listOf("[\"EVENT\",${event.toJsonString()}]"), socket.sent)
            assertEquals(0, socket.request().headers.size)
            socket.receive(rejection)
            assertTrue(socket.cancelled)
            assertEquals(1, socket.sent.size)
            assertFalse(results.last())
        }
        publisher.send(event, listOf(URL), { true }, results::add)
        sockets.last().open()
        sockets.last().receive("[\"OK\",\"${event.id}\",true,\"\"]")
        assertEquals(listOf(false, false, true), results)
        assertEquals(3, sockets.toSet().size)
        assertTrue(sockets.all { it.cancelled && it.sent.size == 1 })
    }

    @Test
    fun timeoutAndAccountResetCancelAnonymousSockets() = runTest {
        val sockets = mutableListOf<Socket>()
        val manager = NostrRelayManager(
            scope = this, eventDeduplicator = NostrEventDeduplicator(maxCapacity = 8),
            privateWebSocketFactory = { request, listener -> Socket(request, listener).also(sockets::add) }
        )
        val event = event()
        val results = mutableListOf<Boolean>()
        manager.sendPrivateEvent(event, listOf(URL), results::add)
        sockets.last().open()
        runCurrent()
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(listOf(false), results)
        manager.sendPrivateEvent(event, listOf(URL), results::add)
        val replacement = sockets.last()
        val token = manager.beginAccountReset()
        assertTrue(manager.discardForAccountReset(token))
        replacement.open()
        replacement.receive("[\"OK\",\"${event.id}\",true,\"\"]")
        assertTrue(replacement.sent.isEmpty())
        assertTrue(sockets.all { it.cancelled })
        assertEquals(listOf(false, false), results)
        manager.sendPrivateEvent(event, listOf(URL), results::add)
        assertEquals(2, sockets.size)
        assertEquals(listOf(false, false, false), results)
    }

    private fun event() = NostrEvent(
        id = "ab".repeat(32), pubkey = "cd".repeat(32), createdAt = 1, kind = 1060,
        tags = listOf(listOf("v", "2")), content = "synthetic-ciphertext", sig = "00".repeat(64)
    )

    private class Socket(private val request: Request, val listener: WebSocketListener) : WebSocket {
        val sent = mutableListOf<String>()
        var cancelled = false
        fun open() = listener.onOpen(this, Response.Builder().request(request)
            .protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build())
        fun receive(text: String) = listener.onMessage(this, text)
        override fun request() = request
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { sent += text; return true }
        override fun send(bytes: ByteString) = false
        override fun close(code: Int, reason: String?) = true
        override fun cancel() { cancelled = true }
    }

    companion object { private const val URL = "wss://private-publish.example" }
}
