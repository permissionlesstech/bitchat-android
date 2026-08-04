package com.bitchat.android.model

import com.bitchat.android.protocol.BinaryProtocol
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnounceV2Test {
    @Test
    fun `announce v2 canonical payload round trips`() {
        val announce = jointVectorAnnounce()

        val encoded = announce.encode()
        val decoded = AnnounceV2.decode(encoded)!!

        assertEquals(100u, decoded.epoch)
        assertArrayEquals(announce.recognitionTags, decoded.recognitionTags)
        assertTrue(decoded.capabilities.contains(PeerCapabilities.PEER_ID_ROTATION))
        assertEquals(null, decoded.bridgeGeohash)
    }

    @Test
    fun `announce v2 rejects duplicate malformed and noncanonical required fields`() {
        val encoded = jointVectorAnnounce().encode()

        assertNull(AnnounceV2.decode(encoded + byteArrayOf(0x01, 0x04, 0, 0, 0, 100)))
        assertNull(AnnounceV2.decode(encoded.copyOf(encoded.size - 1)))
        assertNull(AnnounceV2.decode(encoded.replaceTlv(0x03, byteArrayOf(0x00, 0x40, 0x00))))
    }

    @Test
    fun `joint full announce v2 packet vector is stable`() {
        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.ANNOUNCE_V2.value,
            senderID = "f7c08c528506a374".hex(),
            timestamp = 1_700_000_000_000uL,
            payload = jointVectorAnnounce().encode(),
            ttl = 3u
        )

        val encoded = BinaryProtocol.encode(packet, padding = false)!!

        assertArrayEquals(JOINT_ANNOUNCE_PACKET.hex(), encoded)
        assertEquals(MessageType.ANNOUNCE_V2.value, BinaryProtocol.decode(encoded)!!.type)
    }

    @Test
    fun `Android outer decoder tolerates trailing bytes`() {
        val packet = BitchatPacket(
            type = MessageType.ANNOUNCE_V2.value,
            senderID = ByteArray(8),
            timestamp = 0uL,
            payload = jointVectorAnnounce().encode(),
            ttl = 0u
        )
        val encoded = BinaryProtocol.encode(packet, padding = false)!!

        assertEquals(packet.payload.toList(), BinaryProtocol.decode(encoded + byteArrayOf(0x7F))!!.payload.toList())
    }

    private fun jointVectorAnnounce(): AnnounceV2 {
        val realTags = "4568f61d61d6cbfb5313c7731f629959".hex()
        val padding = ByteArray(48) { it.toByte() }
        return AnnounceV2(
            epoch = 100u,
            recognitionTags = realTags + padding,
            capabilities = PeerCapabilities.PEER_ID_ROTATION
        )
    }

    private fun ByteArray.replaceTlv(type: Int, replacement: ByteArray): ByteArray {
        var offset = 0
        val output = mutableListOf<Byte>()
        while (offset < size) {
            val currentType = this[offset].toInt() and 0xFF
            val length = this[offset + 1].toInt() and 0xFF
            val value = copyOfRange(offset + 2, offset + 2 + length)
            output += currentType.toByte()
            val chosen = if (currentType == type) replacement else value
            output += chosen.size.toByte()
            output += chosen.toList()
            offset += 2 + length
        }
        return output.toByteArray()
    }

    companion object {
        private const val JOINT_ANNOUNCE_PACKET =
            "012c030000018bcfe5680000004cf7c08c528506a37401040000006402404568f61d61d6cbfb" +
                "5313c7731f629959000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" +
                "202122232425262728292a2b2c2d2e2f03020040"
        private fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
