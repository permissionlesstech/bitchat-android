package com.bitchat.android.nostr

import com.bitchat.android.model.CourierEnvelope
import com.bitchat.android.model.NoisePayload
import com.bitchat.android.model.NoisePayloadType
import com.bitchat.android.model.PrivateMessagePacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class BridgeCourierServiceTest {

    private val now = 1_750_000_000_000L
    private val relays = listOf("wss://bridge-test.invalid")
    private val recipientKey = ByteArray(32) { 0x32 }

    @Test
    fun `deposit creates a signed iOS-compatible event and receiver admits it once`() {
        val senderRelay = FakeRelay()
        val sender = BridgeCourierService(
            cipher = FakeCipher(ByteArray(32) { 0x11 }),
            onEnvelope = {},
            relayManager = senderRelay,
            relayUrls = relays,
            clock = { now },
            identityFactory = { NostrIdentity.fromSeed("bridge-courier-sender") }
        )
        var accepted = false

        val event = try {
            assertTrue(sender.deposit("synthetic bridge payload", "bridge-message", recipientKey) {
                accepted = true
            })
            assertTrue(accepted)
            assertEquals(1, senderRelay.sentEvents.size)

            senderRelay.sentEvents.single().also {
                assertEquals(1401, it.kind)
                assertTrue(it.isValidSignature())
                assertEquals(relays, senderRelay.lastSendRelays)
            }
        } finally {
            sender.stop()
        }

        val encoded = Base64.getDecoder().decode(event.content)
        val envelope = requireNotNull(CourierEnvelope.decode(encoded))
        assertTrue(envelope.matchesRecipient(recipientKey, now))
        assertEquals(1u.toUByte(), envelope.copies)
        assertEquals(
            listOf("x", envelope.recipientTag.toHex()),
            event.tags.first { it.firstOrNull() == "x" }
        )
        assertEquals(
            listOf("expiration", (envelope.expiry / 1000u).toString()),
            event.tags.first { it.firstOrNull() == "expiration" }
        )
        val typed = requireNotNull(NoisePayload.decode(envelope.ciphertext))
        assertEquals(NoisePayloadType.PRIVATE_MESSAGE, typed.type)
        val privateMessage = requireNotNull(PrivateMessagePacket.decode(typed.data))
        assertEquals("bridge-message", privateMessage.messageID)
        assertEquals("synthetic bridge payload", privateMessage.content)

        val receiverRelay = FakeRelay()
        val received = mutableListOf<CourierEnvelope>()
        val receiver = BridgeCourierService(
            cipher = FakeCipher(recipientKey),
            onEnvelope = received::add,
            relayManager = receiverRelay,
            relayUrls = relays,
            clock = { now },
            identityFactory = { NostrIdentity.fromSeed("bridge-courier-receiver") }
        )
        try {
            receiver.start()
            assertNotNull(receiverRelay.subscription)
            assertTrue(receiverRelay.subscription!!.filter.matches(event))
            assertEquals(relays, receiverRelay.subscription!!.relayUrls)

            receiverRelay.emit(event)
            receiverRelay.emit(event)

            assertEquals(listOf(envelope), received)
        } finally {
            receiver.stop()
        }
        assertEquals(1, receiverRelay.unsubscribedIDs.size)
    }

    @Test
    fun `receiver rejects a validly signed event with mismatched metadata and deposit fails closed`() {
        val relay = FakeRelay()
        val received = mutableListOf<CourierEnvelope>()
        val receiver = BridgeCourierService(
            cipher = FakeCipher(recipientKey),
            onEnvelope = received::add,
            relayManager = relay,
            relayUrls = relays,
            clock = { now },
            identityFactory = { NostrIdentity.fromSeed("bridge-courier-receiver") }
        )
        val envelope = CourierEnvelope(
            recipientTag = CourierEnvelope.recipientTag(recipientKey, CourierEnvelope.epochDay(now)),
            expiry = (now + CourierEnvelope.MAX_LIFETIME_MS).toULong(),
            ciphertext = byteArrayOf(1, 2, 3),
            copies = 1u
        )
        val identity = NostrIdentity.fromSeed("bridge-courier-invalid-event")
        val mismatchedTag = identity.signEvent(
            NostrEvent(
                pubkey = identity.publicKeyHex,
                createdAt = (now / 1000).toInt(),
                kind = 1401,
                tags = listOf(
                    listOf("x", "00".repeat(CourierEnvelope.TAG_LENGTH)),
                    listOf("expiration", (envelope.expiry / 1000u).toString())
                ),
                content = Base64.getEncoder().encodeToString(requireNotNull(envelope.encode()))
            )
        )
        try {
            receiver.start()
            relay.emit(mismatchedTag)
            assertTrue(received.isEmpty())

            val oversizedSender = BridgeCourierService(
                cipher = FakeCipher(ByteArray(32) { 0x21 }, ByteArray(CourierEnvelope.MAX_CIPHERTEXT_BYTES + 1)),
                onEnvelope = {},
                relayManager = relay,
                relayUrls = relays,
                clock = { now },
                identityFactory = { NostrIdentity.fromSeed("bridge-courier-oversized") }
            )
            try {
                assertFalse(oversizedSender.depositPayload(byteArrayOf(7), recipientKey))

                relay.connected = false
                assertFalse(oversizedSender.depositPayload(byteArrayOf(7), recipientKey))
            } finally {
                oversizedSender.stop()
            }
        } finally {
            receiver.stop()
        }
    }

    @Test
    fun `deposit completion waits for relay acceptance`() {
        val relay = FakeRelay().apply { acceptImmediately = false }
        val sender = BridgeCourierService(
            cipher = FakeCipher(ByteArray(32) { 0x11 }),
            onEnvelope = {},
            relayManager = relay,
            relayUrls = relays,
            clock = { now },
            identityFactory = { NostrIdentity.fromSeed("bridge-courier-pending") }
        )
        var accepted = 0

        try {
            assertTrue(sender.depositPayload(byteArrayOf(1, 2, 3), recipientKey) { accepted++ })
            assertEquals(0, accepted)

            relay.acceptPendingEvent()
            assertEquals(1, accepted)
        } finally {
            sender.stop()
        }
    }

    private class FakeCipher(
        private val staticKey: ByteArray,
        private val sealedOverride: ByteArray? = null
    ) : BridgeCourierCipher {
        override fun staticPublicKey(): ByteArray = staticKey

        override fun seal(payload: ByteArray, recipientNoiseKey: ByteArray): ByteArray =
            sealedOverride ?: payload.copyOf()
    }

    private class FakeRelay : BridgeCourierRelay {
        data class Subscription(
            val filter: NostrFilter,
            val id: String,
            val relayUrls: List<String>,
            val handler: (NostrEvent) -> Unit
        )

        var connected = true
        var acceptImmediately = true
        var subscription: Subscription? = null
        val sentEvents = mutableListOf<NostrEvent>()
        var lastSendRelays: List<String> = emptyList()
        val unsubscribedIDs = mutableListOf<String>()
        private var pendingAccepted: (() -> Unit)? = null

        override fun subscribe(
            filter: NostrFilter,
            id: String,
            targetRelayUrls: List<String>,
            handler: (NostrEvent) -> Unit
        ) {
            subscription = Subscription(filter, id, targetRelayUrls, handler)
        }

        override fun unsubscribe(id: String) {
            unsubscribedIDs += id
            if (subscription?.id == id) subscription = null
        }

        override fun hasConnectedRelay(relayUrls: Collection<String>): Boolean = connected

        override fun sendEvent(
            event: NostrEvent,
            relayUrls: List<String>,
            onAccepted: () -> Unit
        ): Boolean {
            if (!connected) return false
            sentEvents += event
            lastSendRelays = relayUrls
            if (acceptImmediately) onAccepted() else pendingAccepted = onAccepted
            return true
        }

        fun acceptPendingEvent() {
            val callback = requireNotNull(pendingAccepted)
            pendingAccepted = null
            callback()
        }

        fun emit(event: NostrEvent) {
            subscription?.handler?.invoke(event)
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
