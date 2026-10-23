package com.bitchat.android.nostr

import android.content.Context
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Live integration contract shared by the JVM and Android runners; all storage must be disposable. */
internal object NostrLiveRelayAuthContract {
    fun verify(context: Context, relayUrl: String, csvHosts: Set<String>): String {
        require(relayUrl.startsWith("wss://")) { "A secure relay URL is required" }
        require(relayUrl.removePrefix("wss://").trimEnd('/') in csvHosts) { "Relay must be in the app CSV" }
        // This sentinel is deliberately not a geohash and cannot encode a user's location.
        val identity = NostrIdentityBridge.deriveIdentity("synthetic-live-channel", context)
        NostrIdentityBridge.clearGeohashIdentityCache(listOf("synthetic-live-channel"))
        val restoredIdentity = NostrIdentityBridge.deriveIdentity("synthetic-live-channel", context)
        val identityRestored = identity.publicKeyHex == restoredIdentity.publicKeyHex
        assertTrue("Synthetic channel identity must survive reloading secure storage", identityRestored)
        val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).build()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val probe = ObservedConnection(client, identity)
        val manager = NostrRelayManager(
            listOf(relayUrl),
            { error("A channel probe must never request the account identity") },
            { probe },
            scope
        )
        try {
            // No channel/location tags, user data, historical queries, or EVENT publication.
            manager.subscribe(
                filter = NostrFilter.giftWrapsFor(identity.publicKeyHex, System.currentTimeMillis()).copy(limit = 1),
                id = SUBSCRIPTION_ID,
                handler = {},
                targetRelayUrls = listOf(relayUrl),
                authenticationIdentity = { identity }
            )
            manager.connect()
            val completed = probe.completed.await(40, TimeUnit.SECONDS)
            val summary = "challenge=${probe.challenge.get()}, authRequired=${probe.authRequired.get()}, " +
                "proofAccepted=${probe.proofAccepted.get()}, requests=${probe.requests.get()}, " +
                "eose=${probe.eose.get()}, failed=${probe.failed.get()}, identityRestored=$identityRestored"
            assertTrue(summary, completed)
            assertTrue(summary, probe.challenge.get())
            assertTrue(summary, probe.authRequired.get())
            assertTrue(summary, probe.proofAccepted.get())
            assertTrue(summary, probe.requests.get() >= 2)
            assertTrue(summary, probe.eose.get())
            assertFalse(summary, probe.invalidProof.get())
            assertFalse(summary, probe.publishedEvent.get())
            return summary
        } finally {
            manager.disconnect()
            scope.cancel()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private class ObservedConnection(
        private val client: OkHttpClient,
        private val identity: NostrIdentity
    ) : WebSocket.Factory {
        val challenge = AtomicBoolean()
        val authRequired = AtomicBoolean()
        val proofAccepted = AtomicBoolean()
        val requests = AtomicInteger()
        val eose = AtomicBoolean()
        val failed = AtomicBoolean()
        val invalidProof = AtomicBoolean()
        val publishedEvent = AtomicBoolean()
        val completed = CountDownLatch(1)
        @Volatile private var proofId: String? = null

        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            val observed = object : WebSocket {
                lateinit var delegate: WebSocket
                override fun request(): Request = request
                override fun queueSize(): Long = delegate.queueSize()
                override fun send(text: String): Boolean {
                    val frame = JsonParser.parseString(text).asJsonArray
                    when (frame[0].asString) {
                        "REQ" -> requests.incrementAndGet()
                        "EVENT" -> publishedEvent.set(true)
                        "AUTH" -> {
                            val event = requireNotNull(NostrEvent.fromJsonString(frame[1].toString()))
                            proofId = event.id
                            invalidProof.set(event.pubkey != identity.publicKeyHex || !event.isValidSignature())
                        }
                    }
                    return delegate.send(text)
                }
                override fun send(bytes: ByteString): Boolean = delegate.send(bytes)
                override fun close(code: Int, reason: String?): Boolean = delegate.close(code, reason)
                override fun cancel() = delegate.cancel()
            }
            observed.delegate = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen(observed, response)
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val frame = JsonParser.parseString(text).asJsonArray
                    when (frame[0].asString) {
                        "AUTH" -> challenge.set(true)
                        "CLOSED" -> if (frame.size() >= 3 && frame[1].asString == SUBSCRIPTION_ID &&
                            frame[2].asString.startsWith("auth-required:")
                        ) authRequired.set(true)
                        "OK" -> if (frame.size() >= 3 && frame[1].asString == proofId &&
                            frame[2].isJsonPrimitive && frame[2].asJsonPrimitive.isBoolean && frame[2].asBoolean
                        ) proofAccepted.set(true)
                        "EOSE" -> if (frame[1].asString == SUBSCRIPTION_ID && proofAccepted.get()) {
                            eose.set(true)
                            completed.countDown()
                        }
                    }
                    listener.onMessage(observed, text)
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) =
                    listener.onClosing(observed, code, "Relay closed")
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                    listener.onClosed(observed, code, "Relay closed")
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    failed.set(true)
                    listener.onFailure(observed, IllegalStateException("Live relay unavailable"), response)
                }
            })
            return observed
        }
    }

    private const val SUBSCRIPTION_ID = "synthetic-nip42-probe"
}
