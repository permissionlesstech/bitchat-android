package com.bitchat.android.nostr

import android.os.Build
import com.bitchat.android.model.NoisePayload
import com.bitchat.android.model.NoisePayloadType
import com.bitchat.android.model.PrivateMessagePacket
import com.bitchat.android.protocol.BitchatPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

/**
 * Geohash (recipient-less) envelopes must never carry the mesh peer ID: it is the
 * stable Noise fingerprint prefix, so it would link a geohash persona to the device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class NostrEmbeddedBitChatTest {

    @Test
    fun `geohash private message carries a fresh random sender ID`() {
        val first = decode(NostrEmbeddedBitChat.encodePMForNostrNoRecipient("hi", "m1"))
        val second = decode(NostrEmbeddedBitChat.encodePMForNostrNoRecipient("hi", "m1"))

        assertEquals(8, first.senderID.size)
        assertFalse(
            "Each envelope must use its own sender ID",
            first.senderID.contentEquals(second.senderID)
        )
        assertNull(first.recipientID)

        val payload = NoisePayload.decode(first.payload)!!
        assertEquals(NoisePayloadType.PRIVATE_MESSAGE, payload.type)
        val pm = PrivateMessagePacket.decode(payload.data)!!
        assertEquals("m1", pm.messageID)
        assertEquals("hi", pm.content)
    }

    @Test
    fun `geohash acks carry a fresh random sender ID`() {
        for (type in listOf(NoisePayloadType.DELIVERED, NoisePayloadType.READ_RECEIPT)) {
            val first = decode(NostrEmbeddedBitChat.encodeAckForNostrNoRecipient(type, "m1"))
            val second = decode(NostrEmbeddedBitChat.encodeAckForNostrNoRecipient(type, "m1"))

            assertEquals(8, first.senderID.size)
            assertFalse(first.senderID.contentEquals(second.senderID))
            assertNull(first.recipientID)
            val payload = NoisePayload.decode(first.payload)!!
            assertEquals(type, payload.type)
            assertEquals("m1", String(payload.data, Charsets.UTF_8))
        }
    }

    @Test
    fun `favorites envelope still carries the given sender ID`() {
        val senderPeerID = "0011223344556677"
        val packet = decode(
            NostrEmbeddedBitChat.encodePMForNostr(
                content = "hi",
                messageID = "m1",
                recipientPeerID = "8899aabbccddeeff",
                senderPeerID = senderPeerID
            )
        )

        assertEquals(senderPeerID, packet.senderID.joinToString("") { "%02x".format(it) })
    }

    private fun decode(embedded: String?): BitchatPacket {
        val content = requireNotNull(embedded).removePrefix("bitchat1:")
        return requireNotNull(BitchatPacket.fromBinaryData(Base64.getUrlDecoder().decode(content)))
    }
}
