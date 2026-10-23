package com.bitchat.android.nostr

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NostrRelayAuthenticatedReplayRobolectricTest {
    @Test
    fun `reset during authenticated replay discards the remaining snapshot deliveries`() {
        RelayFixture().use { relay ->
            val first = relay.event("synthetic-first")
            val removed = relay.event("synthetic-removed")
            relay.publish(first)
            relay.publish(removed)
            val proofId = relay.requireAuthentication(first, removed)

            relay.afterNextPublication = { relay.manager.clearAllSubscriptions() }
            relay.receive("OK", proofId, true, "")

            // The first replay triggers reset while the retry loop already holds both deliveries.
            assertEquals(listOf(first.id), relay.publicationIds())
        }
    }

    @Test
    fun `revoked signer cannot replay a publication after its earlier proof is accepted`() {
        assertSignerChangePreventsReplay(null)
    }

    @Test
    fun `replacement signer cannot replay a publication under the previous identity proof`() {
        assertSignerChangePreventsReplay(NostrIdentity.fromPrivateKey("0".repeat(63) + "2"))
    }

    @Test
    fun `reset during signer resolution prevents the cleared publication from being replayed`() {
        RelayFixture().use { relay ->
            val removed = relay.event("synthetic-removed")
            var resetOnResolution = false
            relay.publish(removed) {
                if (resetOnResolution) relay.manager.clearAllSubscriptions()
                relay.identity
            }
            val proofId = relay.requireAuthentication(removed)

            resetOnResolution = true
            relay.receive("OK", proofId, true, "")

            assertTrue(relay.publicationIds().isEmpty())
        }
    }

    private fun assertSignerChangePreventsReplay(replacement: NostrIdentity?) {
        RelayFixture().use { relay ->
            var signer: NostrIdentity? = relay.identity
            val first = relay.event("synthetic-first")
            val revoked = relay.event("synthetic-revoked")
            relay.publish(first)
            relay.publish(revoked) { signer }
            val proofId = relay.requireAuthentication(first, revoked)

            relay.afterNextPublication = { signer = replacement }
            relay.receive("OK", proofId, true, "")

            assertEquals(listOf(first.id), relay.publicationIds())
        }
    }

    /** Synchronous transport keeps negative assertions deterministic, without sleeps or a network. */
    private class RelayFixture : AutoCloseable {
        val identity = NostrIdentity.fromPrivateKey("0".repeat(63) + "1")
        var afterNextPublication: (() -> Unit)? = null
        private val frames = mutableListOf<JsonArray>()
        private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        private val gson = NostrRequest.createGson()
        private val request = Request.Builder().url("wss://relay.example/").build()
        private lateinit var listener: WebSocketListener
        private val socket = object : WebSocket {
            override fun request(): Request = request
            override fun queueSize(): Long = 0L

            override fun send(text: String): Boolean {
                val frame = JsonParser.parseString(text).asJsonArray
                frames.add(frame)
                if (frame[0].asString == "EVENT") {
                    val action = afterNextPublication
                    afterNextPublication = null
                    action?.invoke()
                }
                return true
            }

            override fun send(bytes: ByteString): Boolean = error("Unexpected binary frame")
            override fun close(code: Int, reason: String?): Boolean = true
            override fun cancel() = Unit
        }
        val manager = NostrRelayManager(
            initialRelayUrls = listOf(request.url.toString()),
            identityProvider = { identity },
            clientProvider = {
                WebSocket.Factory { _, callbacks ->
                    listener = callbacks
                    socket
                }
            },
            scope = scope
        )

        init {
            manager.connect()
            listener.onOpen(
                socket,
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(101)
                    .message("Synthetic upgrade")
                    .build()
            )
            assertTrue(manager.isConnected.value)
        }

        fun event(content: String): NostrEvent = NostrEvent(
            pubkey = identity.publicKeyHex,
            createdAt = 1_700_000_000,
            kind = NostrKind.TEXT_NOTE,
            tags = emptyList(),
            content = content
        ).sign(identity.privateKeyHex)

        fun publish(event: NostrEvent, signer: () -> NostrIdentity? = { identity }) {
            manager.sendEvent(event, authenticationIdentity = signer)
        }

        fun requireAuthentication(vararg events: NostrEvent): String {
            assertEquals(events.map { it.id }, publicationIds())
            frames.clear()
            receive("AUTH", "synthetic-challenge")
            events.forEach { receive("OK", it.id, false, "auth-required: authenticate") }
            val proof = frames.single()
            assertEquals("AUTH", proof[0].asString)
            frames.clear()
            return proof[1].asJsonObject["id"].asString
        }

        fun receive(vararg values: Any) {
            listener.onMessage(socket, gson.toJson(values))
        }

        fun publicationIds(): List<String> = frames.map { frame ->
            assertEquals("EVENT", frame[0].asString)
            frame[1].asJsonObject["id"].asString
        }

        override fun close() {
            manager.disconnect()
            scope.cancel()
        }
    }
}
