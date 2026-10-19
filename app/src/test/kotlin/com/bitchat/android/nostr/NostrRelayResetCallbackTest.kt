package com.bitchat.android.nostr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
class NostrRelayResetCallbackTest {
    @Test
    fun `reset confirmation callback runs outside account locks`() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val manager = NostrRelayManager(
            scope = scope,
            eventDeduplicator = NostrEventDeduplicator(maxCapacity = 8)
        )
        installConnection(manager, RELAY_URL, RecordingWebSocket())
        val callbackReachedAccountLock = AtomicBoolean(false)
        val event = signedEvent()
        manager.sendEventConfirmed(event, listOf(RELAY_URL)) {
            val lockReached = CountDownLatch(1)
            thread(start = true, name = "relay-reset-callback-lock-probe") {
                manager.registerPendingGiftWrap(
                    id = "callback-probe",
                    expectedAccountGeneration = manager.captureAccountGeneration()
                )
                lockReached.countDown()
            }
            callbackReachedAccountLock.set(lockReached.await(1, TimeUnit.SECONDS))
        }

        val resetToken = manager.beginAccountReset()
        val resetFinished = CountDownLatch(1)
        thread(start = true, name = "relay-reset-probe") {
            manager.discardForAccountReset(resetToken)
            resetFinished.countDown()
        }

        try {
            assertTrue(resetFinished.await(2, TimeUnit.SECONDS))
            assertTrue(callbackReachedAccountLock.get())
            assertTrue(manager.completeAccountReset(resetToken))
        } finally {
            scope.cancel()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun installConnection(
        manager: NostrRelayManager,
        relayUrl: String,
        socket: WebSocket
    ) {
        val field = NostrRelayManager::class.java.getDeclaredField("connections")
        field.isAccessible = true
        val connections = field.get(manager) as MutableMap<String, WebSocket>
        connections[relayUrl] = socket
    }

    private fun signedEvent(): NostrEvent {
        val privateKey = "0".repeat(63) + "1"
        return NostrEvent(
            pubkey = NostrCrypto.derivePublicKey(privateKey),
            createdAt = 1,
            kind = 1060,
            tags = emptyList(),
            content = "ciphertext"
        ).sign(privateKey)
    }

    private class RecordingWebSocket : WebSocket {
        override fun request(): Request =
            Request.Builder().url(RELAY_URL).build()

        override fun queueSize(): Long = 0L
        override fun send(text: String): Boolean = true
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() = Unit
    }

    companion object {
        private const val RELAY_URL = "wss://relay-reset.example"
    }
}
