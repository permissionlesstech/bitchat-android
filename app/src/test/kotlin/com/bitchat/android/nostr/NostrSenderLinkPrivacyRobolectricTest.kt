package com.bitchat.android.nostr

import com.bitchat.android.model.NoisePayload
import com.bitchat.android.model.NoisePayloadType
import com.bitchat.android.model.PrivateMessagePacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.services.ContactIdentityResolver
import com.bitchat.android.util.AppConstants
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NostrSenderLinkPrivacyRobolectricTest {
    private val meshId = "0123456789abcdef"
    private val meshBytes = byteArrayOf(1, 35, 69, 103, -119, -85, -51, -17)
    private val persona = NostrIdentity.fromPrivateKey("1".padStart(64, '0'))
    private val recipient = NostrIdentity.fromPrivateKey("3".padStart(64, '0'))
    private val outsider = NostrIdentity.fromPrivateKey("4".padStart(64, '0'))

    private fun geohashContent(type: NoisePayloadType): String = requireNotNull(
        if (type == NoisePayloadType.PRIVATE_MESSAGE) {
            NostrEmbeddedBitChat.encodePMForNostrNoRecipient("synthetic message", "synthetic-id")
        } else {
            NostrEmbeddedBitChat.encodeAckForNostrNoRecipient(type, "synthetic-id")
        }
    )

    private fun decode(content: String): BitchatPacket = requireNotNull(
        BitchatPacket.fromBinaryData(Base64.getUrlDecoder().decode(content.removePrefix("bitchat1:")))
    )

    @Test
    fun `no recipient envelopes match v1 wire fixtures outside timestamp and random sender`() {
        val fixtures = requireNotNull(javaClass.getResourceAsStream("/contracts/nostr-no-recipient-v1.txt"))
            .bufferedReader().use { it.readLines() }.filter { it.isNotBlank() && !it.startsWith("#") }
        for (line in fixtures) {
            val (name, hex) = line.split(" ")
            val type = NoisePayloadType.valueOf(name)
            val expected = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val content = requireNotNull(
                if (type == NoisePayloadType.PRIVATE_MESSAGE) {
                    NostrEmbeddedBitChat.encodePMForNostrNoRecipient("hi", "id")
                } else {
                    NostrEmbeddedBitChat.encodeAckForNostrNoRecipient(type, "id")
                }
            )
            val bytes = Base64.getUrlDecoder().decode(content.removePrefix("bitchat1:"))
            assertEquals(256, bytes.size)
            // v1 timestamp occupies bytes 3..10; random sender occupies bytes 14..21.
            val normalized = bytes.copyOfRange(0, expected.size)
            normalized.fill(0, 3, 11)
            normalized.fill(0, 14, 22)
            assertArrayEquals(name, expected, normalized)
            val padding = (256 - expected.size).toByte()
            assertTrue(bytes.drop(expected.size).all { it == padding })
            val decoded = requireNotNull(BitchatPacket.fromBinaryData(expected))
            assertEquals(type, NoisePayload.decode(decoded.payload)?.type)
            assertNull(decoded.recipientID)
            assertNull(decoded.signature)
        }
    }

    @Test
    fun `geohash envelopes use changing eight byte headers and preserve authenticated payloads`() {
        for (type in listOf(NoisePayloadType.PRIVATE_MESSAGE, NoisePayloadType.DELIVERED, NoisePayloadType.READ_RECEIPT)) {
            val firstContent = geohashContent(type)
            val second = decode(geohashContent(type))
            val wrap = NostrProtocol.createPrivateMessage(firstContent, recipient.publicKeyHex, persona).single()
            assertNull(NostrProtocol.decryptPrivateMessage(wrap, outsider))
            val opened = requireNotNull(NostrProtocol.decryptPrivateMessage(wrap, recipient))
            assertEquals(persona.publicKeyHex, opened.second)
            val first = decode(opened.first)
            assertEquals(8, first.senderID.size)
            assertFalse(first.senderID.contentEquals(meshBytes))
            assertFalse(first.senderID.contentEquals(second.senderID))
            assertArrayEquals(first.payload, second.payload)
            assertEquals(MessageType.NOISE_ENCRYPTED.value, first.type)
            assertEquals(1.toUByte(), first.version)
            assertEquals(AppConstants.MESSAGE_TTL_HOPS, first.ttl)
            assertNull(first.recipientID)
            assertNull(first.signature)
            val payload = requireNotNull(NoisePayload.decode(first.payload))
            assertEquals(type, payload.type)
            if (type == NoisePayloadType.PRIVATE_MESSAGE) {
                val message = requireNotNull(PrivateMessagePacket.decode(payload.data))
                assertEquals("synthetic-id", message.messageID)
                assertEquals("synthetic message", message.content)
            } else {
                assertEquals("synthetic-id", String(payload.data, Charsets.UTF_8))
            }
        }
    }

    @Test
    fun `recipient addressed messages and receipts keep mesh identity fields`() {
        val recipientId = "fedcba9876543210"
        val recipientBytes = byteArrayOf(-2, -36, -70, -104, 118, 84, 50, 16)
        val contents = listOf(
            NostrEmbeddedBitChat.encodePMForNostr("synthetic favorite message", "account-id", recipientId, meshId),
            NostrEmbeddedBitChat.encodeAckForNostr(NoisePayloadType.DELIVERED, "account-id", recipientId, meshId),
            NostrEmbeddedBitChat.encodeAckForNostr(NoisePayloadType.READ_RECEIPT, "account-id", recipientId, meshId)
        )
        for (content in contents) {
            val packet = decode(requireNotNull(content))
            assertArrayEquals(meshBytes, packet.senderID)
            assertArrayEquals(recipientBytes, packet.recipientID)
        }
    }

    @Test
    fun `recipient addressed noise key normalization is preserved`() {
        val noiseKey = "11".repeat(32)
        val expected = requireNotNull(ContactIdentityResolver.peerIdForNoiseKeyHex(noiseKey))
        val packet = decode(requireNotNull(NostrEmbeddedBitChat.encodePMForNostr("synthetic", "id", noiseKey, meshId)))
        assertArrayEquals(requireNotNull(ContactIdentityResolver.bytesFromHex(expected)), packet.recipientID)
        assertArrayEquals(meshBytes, packet.senderID)
    }

    @Test
    fun `geohash ack encoder rejects message payload type`() {
        assertNull(NostrEmbeddedBitChat.encodeAckForNostrNoRecipient(NoisePayloadType.PRIVATE_MESSAGE, "id"))
    }
}
