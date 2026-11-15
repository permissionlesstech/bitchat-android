package com.bitchat.android.nostr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.identity.SecureIdentityStateManager
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
import org.mockito.Mockito.mockConstruction
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NostrRelayAuthenticationLifecycleRobolectricTest {
    @Test
    fun `anonymous globe observer does not disclose persistent account identity`() {
        Fixture().use { relay ->
            val contextField = NostrRelayManager::class.java.getDeclaredField("appContext")
            contextField.isAccessible = true
            contextField.set(relay.manager, ApplicationProvider.getApplicationContext<Context>())
            mockConstruction(SecureIdentityStateManager::class.java) { state, _ ->
                whenever(state.getSecureValue("nostr_private_key")).thenReturn(relay.identity.privateKeyHex)
            }.use { storage ->
                relay.manager.subscribe(
                    filter = NostrFilter(kinds = listOf(NostrKind.EPHEMERAL_EVENT)),
                    id = "synthetic-globe-observer",
                    handler = {},
                    targetRelayUrls = listOf(relay.url),
                    observeOnly = true
                )
                relay.receive("AUTH", "synthetic-observer-challenge")
                relay.receive("CLOSED", "synthetic-globe-observer", "auth-required: authenticate")
                val proofs = relay.frames.filter { it[0].asString == "AUTH" }
                // This setup mirrors GeohashActivityMonitor.subscribe: no account-scoped operation.
                assertTrue("Anonymous observations must not send authentication proofs", proofs.isEmpty())
                assertTrue("Anonymous observations must not load account storage", storage.constructed().isEmpty())
            }
        }
    }

    @Test
    fun `released relay sessions do not accumulate after repeated globe open and close`() {
        Fixture().use { relay ->
            repeat(3) {
                relay.manager.releaseRelays(listOf(relay.url))
                assertEquals("Release must discard authentication before a close callback", 0,
                    relay.authenticationSessionCount())
                relay.closed()
                if (it < 2) {
                    relay.manager.connectToRelays(listOf(relay.url))
                    relay.open()
                }
            }
            assertEquals("Released WebSockets remain retained by authentication state", 0,
                relay.authenticationSessionCount())
        }
    }

    @Test
    fun `unsubscribe during signer resolution prevents stale authenticated subscription replay`() {
        Fixture().use { relay ->
            var unsubscribeOnResolution = false
            relay.manager.subscribe(
                filter = NostrFilter(kinds = listOf(NostrKind.TEXT_NOTE)),
                id = "synthetic-removed",
                handler = {},
                authenticationIdentity = {
                    if (unsubscribeOnResolution) relay.manager.unsubscribe("synthetic-removed")
                    relay.identity
                }
            )
            relay.takeFrames()
            relay.receive("AUTH", "synthetic-challenge")
            relay.receive("CLOSED", "synthetic-removed", "auth-required: authenticate")
            val proof = relay.takeFrames().single()
            assertEquals("AUTH", proof[0].asString)

            unsubscribeOnResolution = true
            relay.receive("OK", proof[1].asJsonObject["id"].asString, true, "")

            assertEquals(listOf("CLOSE"), relay.takeFrames().map { it[0].asString })
        }
    }

    @Test
    fun `reset during signer resolution prevents stale authenticated subscription replay`() {
        assertSubscriptionChangePreventsReplay { manager -> manager.clearAllSubscriptions() }
    }

    @Test
    fun `replacement during signer resolution does not replay the old subscription filter`() {
        assertSubscriptionChangePreventsReplay { manager ->
            manager.subscribe(
                filter = NostrFilter(kinds = listOf(NostrKind.GIFT_WRAP)),
                id = "synthetic-changing",
                handler = {},
                authenticationIdentity = { null }
            )
        }
    }

    private fun assertSubscriptionChangePreventsReplay(change: (NostrRelayManager) -> Unit) {
        Fixture().use { relay ->
            var changeOnResolution = false
            relay.manager.subscribe(
                filter = NostrFilter(kinds = listOf(NostrKind.TEXT_NOTE)),
                id = "synthetic-changing",
                handler = {},
                authenticationIdentity = {
                    if (changeOnResolution) change(relay.manager)
                    relay.identity
                }
            )
            relay.takeFrames()
            relay.receive("AUTH", "synthetic-challenge")
            relay.receive("CLOSED", "synthetic-changing", "auth-required: authenticate")
            val proof = relay.takeFrames().single()
            assertEquals("AUTH", proof[0].asString)

            changeOnResolution = true
            relay.receive("OK", proof[1].asJsonObject["id"].asString, true, "")

            val kinds = relay.takeFrames().filter { it[0].asString == "REQ" }
                .flatMap { it[2].asJsonObject["kinds"].asJsonArray.map { kind -> kind.asInt } }
            assertTrue("The removed filter must not be replayed", NostrKind.TEXT_NOTE !in kinds)
        }
    }

    /** Synchronous synthetic sockets make negative assertions deterministic without a network. */
    private class Fixture : AutoCloseable {
        val identity = NostrIdentity.fromPrivateKey("0".repeat(63) + "1")
        val url = "wss://relay.example/"
        val frames = mutableListOf<JsonArray>()
        private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        private val gson = NostrRequest.createGson()
        private lateinit var listener: WebSocketListener
        private lateinit var socket: WebSocket
        private val request = Request.Builder().url(url).build()
        val manager = NostrRelayManager(
            initialRelayUrls = listOf(url),
            clientProvider = {
                WebSocket.Factory { _, callbacks ->
                    listener = callbacks
                    object : WebSocket {
                        override fun request() = request
                        override fun queueSize() = 0L
                        override fun send(text: String): Boolean {
                            frames += JsonParser.parseString(text).asJsonArray
                            return true
                        }
                        override fun send(bytes: ByteString): Boolean = error("Unexpected binary frame")
                        override fun close(code: Int, reason: String?) = true
                        override fun cancel() = Unit
                    }.also { socket = it }
                }
            },
            scope = scope
        )

        init {
            manager.connect()
            open()
        }

        fun open() {
            listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(101).message("Synthetic upgrade").build())
        }

        fun receive(vararg values: Any) = listener.onMessage(socket, gson.toJson(values))
        fun closed() = listener.onClosed(socket, 1000, "Synthetic release")

        fun takeFrames(): List<JsonArray> = frames.toList().also { frames.clear() }

        fun authenticationSessionCount(): Int {
            val field = NostrRelayManager::class.java.getDeclaredField("authentication")
            field.isAccessible = true
            return (field.get(manager) as Map<*, *>).size
        }

        override fun close() {
            manager.disconnect()
            scope.cancel()
        }
    }
}
