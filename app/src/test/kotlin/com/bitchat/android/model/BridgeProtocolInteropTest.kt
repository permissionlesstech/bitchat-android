package com.bitchat.android.model

import com.bitchat.android.noise.CourierNoiseCrypto
import com.bitchat.android.nostr.MeshMessageIdentity
import com.bitchat.android.nostr.NostrIdentity
import com.bitchat.android.nostr.NostrKind
import com.bitchat.android.nostr.NostrProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeProtocolInteropTest {
    @Test
    fun `carrier TLVs match the iOS wire fixture`() {
        val carrier = NostrCarrierPacket(
            direction = NostrCarrierPacket.Direction.TO_BRIDGE,
            geohash = "u4pruy",
            eventJson = "{}".toByteArray()
        )

        assertEquals(
            "010001030200067534707275790300027b7d",
            carrier.encode().toHex()
        )
        assertEquals(carrier, NostrCarrierPacket.decode(carrier.encode()))
        assertNull(NostrCarrierPacket.decode(carrier.encode().dropLast(1).toByteArray()))
    }

    @Test
    fun `carrier decoder skips unknown TLVs`() {
        val encoded = NostrCarrierPacket(
            NostrCarrierPacket.Direction.FROM_BRIDGE,
            "u4pruy",
            "{}".toByteArray()
        ).encode()
        val withUnknown = encoded + byteArrayOf(0x7F, 0x00, 0x02, 0x12, 0x34)

        assertEquals(
            NostrCarrierPacket.Direction.FROM_BRIDGE,
            NostrCarrierPacket.decode(withUnknown)?.direction
        )
    }

    @Test
    fun `courier envelope and daily tag match iOS fixtures`() {
        val envelope = CourierEnvelope(
            recipientTag = ByteArray(16) { it.toByte() },
            expiry = 0x0102_0304_0506_0708L,
            ciphertext = byteArrayOf(0xAA.toByte(), 0xBB.toByte()),
            copies = 3,
            prekeyId = 0x89AB_CDEFL
        )

        assertEquals(
            "010010000102030405060708090a0b0c0d0e0f" +
                "0200080102030405060708" +
                "030002aabb04000103" +
                "05000489abcdef",
            envelope.encode()!!.toHex()
        )
        assertEquals(envelope, CourierEnvelope.decode(envelope.encode()!!))
        assertEquals(
            "f7b87836e588a2b31b306605b3313744",
            CourierEnvelope.recipientTag(ByteArray(32) { it.toByte() }, 1).toHex()
        )
    }

    @Test
    fun `signed prekey canonical bytes match iOS fixture`() {
        val bundle = PrekeyBundle(
            noiseStaticPublicKey = ByteArray(32) { 0x11 },
            prekeys = listOf(
                PrekeyBundle.Prekey(0x0102_0304, ByteArray(32) { 0x22 })
            ),
            generatedAt = 0x0102_0304_0506_0708L,
            signature = ByteArray(64) { 0x33 }
        )

        assertEquals(
            "18" +
                "626974636861742d7072656b65792d62756e646c652d7631" +
                "11".repeat(32) +
                "01" +
                "01020304" +
                "22".repeat(32) +
                "0102030405060708",
            bundle.signableBytes().toHex()
        )
        assertEquals(bundle, PrekeyBundle.decode(bundle.encode()!!))
    }

    @Test
    fun `courier Noise X opens static and one-time prekey ciphertexts`() {
        val senderPrivate = ByteArray(32) { (it + 1).toByte() }
        val recipientPrivate = ByteArray(32) { (it + 33).toByte() }
        val payload = "offline hello".toByteArray()

        val staticCiphertext = CourierNoiseCrypto.seal(
            payload,
            senderPrivate,
            CourierNoiseCrypto.publicKey(recipientPrivate)
        )
        val staticOpened = CourierNoiseCrypto.open(staticCiphertext, recipientPrivate)
        assertArrayEquals(payload, staticOpened.payload)
        assertArrayEquals(CourierNoiseCrypto.publicKey(senderPrivate), staticOpened.senderStaticKey)

        val prekey = PrekeyBundle.Prekey(
            id = 0x89AB_CDEFL,
            publicKey = CourierNoiseCrypto.publicKey(recipientPrivate)
        )
        val prekeyCiphertext = CourierNoiseCrypto.sealToPrekey(payload, senderPrivate, prekey)
        val prekeyOpened = CourierNoiseCrypto.openWithPrekey(
            prekeyCiphertext,
            recipientPrivate,
            prekey.id
        )
        assertArrayEquals(payload, prekeyOpened.payload)
        assertArrayEquals(CourierNoiseCrypto.publicKey(senderPrivate), prekeyOpened.senderStaticKey)
    }

    @Test
    fun `public message stable identity matches cross-language fixture`() {
        val id = MeshMessageIdentity.stableId(
            senderIdHex = "0011223344556677",
            timestampMs = 1_750_000_000_123,
            content = "hello mesh"
        )

        assertEquals("b83f94d81dcdd1b0c0048f6645995dd4", id)
        assertTrue(id.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `bridge Nostr event uses signed rendezvous tags`() {
        val identity = NostrIdentity.fromPrivateKey("01".padStart(64, '0'))
        val event = NostrProtocol.createBridgeMeshEvent(
            content = "hello mesh",
            cell = "u4pruy",
            senderIdentity = identity,
            nickname = "alice",
            meshSenderId = "0011223344556677",
            meshTimestampMs = 1_750_000_000_123
        )

        assertEquals(NostrKind.EPHEMERAL_EVENT, event.kind)
        assertEquals(listOf("r", "u4pruy"), event.tags[0])
        assertEquals(listOf("n", "alice"), event.tags[1])
        assertEquals(
            listOf(
                "m",
                "b83f94d81dcdd1b0c0048f6645995dd4",
                "0011223344556677",
                "1750000000123"
            ),
            event.tags[2]
        )
        assertTrue(event.isValidSignature())
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
