package com.bitchat.android.nostr

import com.bitchat.android.geohash.LiveLocationPrivacyGate
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class NostrRelayAuthenticationRobolectricTest {
    @Test
    fun `channel DM subscription proves its channel key independently of the account key`() {
        RelayFixture().use { relay ->
            val channelIdentity = NostrIdentity.fromPrivateKey("0".repeat(63) + "2")
            val original = relay.subscribe(
                "channel-dm",
                authenticationIdentity = { channelIdentity },
                filter = NostrFilter.giftWrapsFor(channelIdentity.publicKeyHex)
            )
            relay.send("AUTH", "channel-challenge")
            relay.send("CLOSED", "channel-dm", "auth-required: authenticate")
            val proof = relay.nextProof("channel-challenge", channelIdentity)
            assertNotEquals(relay.identity.publicKeyHex, proof.pubkey)
            relay.send("OK", proof.id, true, "")
            assertEquals(original, relay.nextMessage())
        }
    }

    @Test
    fun `channel gift wrap authenticates with its channel key rather than the outer or account key`() {
        RelayFixture().use { relay ->
            relay.subscribe("ready")
            val channelIdentity = NostrIdentity.fromPrivateKey("0".repeat(63) + "2")
            val recipient = NostrIdentity.fromPrivateKey("0".repeat(63) + "3")
            val giftWrap = NostrProtocol.createPrivateMessage(
                "synthetic-channel-dm", recipient.publicKeyHex, channelIdentity
            ).single()
            relay.manager.sendEvent(giftWrap, authenticationIdentity = { channelIdentity })
            val original = relay.nextMessage()
            relay.send("AUTH", "channel-write-challenge")
            relay.send("OK", giftWrap.id, false, "auth-required: authenticate")

            val proof = relay.nextProof("channel-write-challenge", channelIdentity)
            assertNotEquals(giftWrap.pubkey, proof.pubkey)
            assertNotEquals(relay.identity.publicKeyHex, proof.pubkey)
            relay.send("OK", proof.id, true, "")
            assertEquals(original, relay.nextMessage())
        }
    }

    @Test
    fun `two channel keys on one relay receive independent proofs and subscription retries`() {
        RelayFixture().use { relay ->
            val firstIdentity = NostrIdentity.fromPrivateKey("0".repeat(63) + "2")
            val secondIdentity = NostrIdentity.fromPrivateKey("0".repeat(63) + "3")
            val firstRequest = relay.subscribe("first-channel", authenticationIdentity = { firstIdentity })
            val secondRequest = relay.subscribe("second-channel", authenticationIdentity = { secondIdentity })
            relay.send("AUTH", "shared-challenge")
            relay.send("CLOSED", "first-channel", "auth-required: authenticate")
            val firstProof = relay.nextProof("shared-challenge", firstIdentity)
            relay.send("CLOSED", "second-channel", "auth-required: authenticate")
            val secondProof = relay.nextProof("shared-challenge", secondIdentity)

            relay.send("OK", firstProof.id, true, "")
            assertEquals(firstRequest, relay.nextMessage())
            relay.send("OK", secondProof.id, true, "")
            assertEquals(secondRequest, relay.nextMessage())
        }
    }

    @Test
    fun `unavailable channel signer never falls back to the account identity`() {
        RelayFixture().use { relay ->
            relay.subscribe("unavailable-channel", authenticationIdentity = { null })
            val allowed = relay.subscribe("available-channel", authenticationIdentity = { relay.identity })
            relay.send("AUTH", "missing-key-challenge")
            relay.send("CLOSED", "unavailable-channel", "auth-required: authenticate")
            relay.send("CLOSED", "available-channel", "auth-required: authenticate")
            val proof = relay.nextProof("missing-key-challenge")
            relay.send("OK", proof.id, true, "")
            // The positive completion signal must be the available request, never the missing-key one.
            assertEquals(allowed, relay.nextMessage())
        }
    }

    @Test
    fun `auth-required subscription authenticates with account key and restores the original request`() {
        RelayFixture().use { relay ->
            val original = relay.subscribe("synthetic-sub")
            relay.send("AUTH", "synthetic-challenge")
            relay.send("CLOSED", "synthetic-sub", "auth-required: authenticate")

            val proof = relay.nextProof("synthetic-challenge")
            relay.send("OK", proof.id, true, "")

            assertEquals(original, relay.nextMessage())
        }
    }

    @Test
    fun `auth-required write replays the same signed event after proof acceptance`() {
        RelayFixture().use { relay ->
            relay.subscribe("ready")
            // Gift wraps can have a different author; AUTH must still prove the account key.
            val event = relay.event(signer = NostrIdentity.fromPrivateKey("0".repeat(63) + "2"))
            relay.manager.sendEvent(event)
            val original = relay.nextMessage()
            assertEquals("EVENT", original[0].asString)
            relay.send("OK", event.id, false, "auth-required: authenticate")
            relay.send("AUTH", "synthetic-write-challenge")

            val proof = relay.nextProof("synthetic-write-challenge")
            assertNotEquals(event.pubkey, proof.pubkey)
            relay.send("OK", proof.id, true, "")

            assertEquals(original, relay.nextMessage())
            relay.send("OK", event.id, true, "")
        }
    }

    @Test
    fun `unacknowledged writes survive reconnect and authenticate against a fresh challenge`() {
        RelayFixture().use { relay ->
            relay.subscribe("ready")
            val event = relay.event()
            relay.manager.sendEvent(event)
            assertEquals("EVENT", relay.nextMessage()[0].asString)
            relay.send("AUTH", "first-challenge")
            relay.send("OK", event.id, false, "auth-required: authenticate")
            val oldProof = relay.nextProof("first-challenge")

            relay.reconnect()
            val restored = listOf(relay.nextMessage(), relay.nextMessage())
            assertEquals(setOf("REQ", "EVENT"), restored.map { it[0].asString }.toSet())
            relay.send("AUTH", "fresh-challenge")
            relay.send("OK", event.id, false, "auth-required: authenticate")
            val newProof = relay.nextProof("fresh-challenge")
            assertNotEquals(oldProof.id, newProof.id)
            relay.send("OK", newProof.id, true, "")
            assertEquals(event.id, relay.nextMessage()[1].asJsonObject["id"].asString)
        }
    }

    @Test
    fun `unsubscribed and revoked requests are absent from authentication replay`() {
        LiveLocationPrivacyGate.update(true)
        try {
            RelayFixture().use { relay ->
                relay.subscribe("removed")
                val token = requireNotNull(LiveLocationPrivacyGate.captureToken())
                val liveEvent = relay.event("synthetic-live")
                relay.manager.sendEvent(liveEvent, liveLocationToken = token)
                assertEquals("EVENT", relay.nextMessage()[0].asString)
                relay.send("AUTH", "privacy-challenge")
                relay.send("CLOSED", "removed", "auth-required: authenticate")
                relay.send("OK", liveEvent.id, false, "auth-required: authenticate")
                val proof = relay.nextProof("privacy-challenge")

                relay.manager.unsubscribe("removed")
                assertEquals("CLOSE", relay.nextMessage()[0].asString)
                LiveLocationPrivacyGate.update(false)
                // This blocked, permitted event provides a positive completion signal after AUTH OK.
                val manual = relay.event("synthetic-manual")
                relay.manager.sendEvent(manual)
                assertEquals(manual.id, relay.nextMessage()[1].asJsonObject["id"].asString)
                relay.send("OK", manual.id, false, "auth-required: authenticate")
                relay.send("OK", proof.id, true, "")

                val replay = relay.nextMessage()
                assertEquals("EVENT", replay[0].asString)
                assertEquals(manual.id, replay[1].asJsonObject["id"].asString)
                assertTrue(relay.messages.isEmpty())
            }
        } finally {
            LiveLocationPrivacyGate.update(false)
        }
    }

    @Test
    fun `ordinary publication continues without proving identity to an optional auth relay`() {
        RelayFixture().use { relay ->
            relay.subscribe("ready")
            relay.send("AUTH", "optional-challenge")
            val event = relay.event()
            relay.manager.sendEvent(event)
            val publication = relay.nextMessage()
            assertEquals("EVENT", publication[0].asString)
            assertEquals(event.id, publication[1].asJsonObject["id"].asString)
            relay.send("OK", event.id, true, "")
        }
    }

    private class RelayFixture : AutoCloseable {
        val identity = NostrIdentity.fromPrivateKey("0".repeat(63) + "1")
        val messages = LinkedBlockingQueue<JsonArray>()
        private val opened = LinkedBlockingQueue<WebSocket>()
        private val server = MockWebServer()
        private val client = OkHttpClient()
        private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        private val gson = NostrRequest.createGson()
        private var socket: WebSocket? = null
        private val url: String
        val manager: NostrRelayManager

        init {
            server.start()
            url = server.url("/").toString().replaceFirst("http", "ws")
            manager = NostrRelayManager(
                initialRelayUrls = listOf(url),
                identityProvider = { identity },
                clientProvider = { client },
                scope = scope
            )
        }

        fun subscribe(
            id: String,
            authenticationIdentity: (() -> NostrIdentity?)? = null,
            filter: NostrFilter = NostrFilter(kinds = listOf(1))
        ): JsonArray {
            // Register before the first connection, with launches executed immediately. This avoids
            // racing the initial subscription send against onOpen's subscription restoration.
            manager.subscribe(
                filter, id, {}, listOf(url),
                authenticationIdentity = authenticationIdentity
            )
            if (socket == null) {
                enqueueConnection()
                manager.connect()
                socket = requireNotNull(opened.poll(10, TimeUnit.SECONDS)) { "Local relay did not open" }
            }
            return nextMessage().also { assertEquals("REQ", it[0].asString) }
        }

        fun event(content: String = "synthetic-message", signer: NostrIdentity = identity) = NostrEvent(
            pubkey = signer.publicKeyHex,
            createdAt = 1_700_000_000,
            kind = 1,
            tags = emptyList(),
            content = content
        ).sign(signer.privateKeyHex)

        fun send(vararg values: Any) {
            assertTrue(requireNotNull(socket).send(gson.toJson(values)))
        }

        fun nextMessage(): JsonArray = requireNotNull(messages.poll(10, TimeUnit.SECONDS)) {
            "Expected client frame from local relay"
        }

        fun nextProof(challenge: String, expectedIdentity: NostrIdentity = identity): NostrEvent {
            val frame = nextMessage()
            assertEquals("AUTH", frame[0].asString)
            val proof = requireNotNull(NostrEvent.fromJsonString(frame[1].toString()))
            assertEquals(22242, proof.kind)
            assertEquals(expectedIdentity.publicKeyHex, proof.pubkey)
            assertEquals("", proof.content)
            assertEquals(listOf(listOf("relay", url), listOf("challenge", challenge)), proof.tags)
            val age = System.currentTimeMillis() / 1000 - proof.createdAt
            assertTrue(age in 0..10)
            assertTrue(proof.isValidSignature())
            return proof
        }

        fun reconnect() {
            manager.disconnect()
            socket?.close(1000, "Synthetic reconnect")
            enqueueConnection()
            manager.connect()
            socket = requireNotNull(opened.poll(10, TimeUnit.SECONDS)) { "Local relay did not reconnect" }
        }

        private fun enqueueConnection() {
            server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened.add(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    messages.add(JsonParser.parseString(text).asJsonArray)
                }
            }).build())
        }

        override fun close() {
            manager.disconnect()
            scope.cancel()
            socket?.close(1000, "Synthetic test complete")
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
            server.close()
        }
    }
}
