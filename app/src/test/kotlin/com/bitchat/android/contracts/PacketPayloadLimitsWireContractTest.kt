package com.bitchat.android.contracts

import com.bitchat.android.protocol.BinaryProtocol
import com.bitchat.android.protocol.CompressionUtil
import com.bitchat.android.protocol.MessageType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Golden vectors for the per-type payload admission rules in
 * docs/PACKET_PAYLOAD_LIMITS.md.
 *
 * Every frame is literal wire bytes with a real raw-DEFLATE body (zlib level 9,
 * window bits -15, over an all-zero payload), decoded through the production
 * decompressor. Sender 1011121314151617, timestamp 0x0102030405060708, TTL 7,
 * flags IS_COMPRESSED, no padding.
 */
class PacketPayloadLimitsWireContractTest {

    @Test
    fun `v1 announce inflating to exactly 4 KiB is accepted`() {
        val packet = BinaryProtocol.decode(hex(ANNOUNCE_4096))

        assertNotNull(packet)
        assertEquals(MessageType.ANNOUNCE.value, packet!!.type)
        assertArrayEquals(ByteArray(4096), packet.payload)
    }

    @Test
    fun `v1 announce inflating one byte past 4 KiB is rejected`() {
        assertNull(BinaryProtocol.decode(hex(ANNOUNCE_4097)))
    }

    @Test
    fun `v2 public message inflating to exactly 128 KiB is accepted`() {
        // 143 deflate bytes for 131,072 bytes (~917:1): a dense real stream stays
        // inside DEFLATE's 1032:1 bound.
        val packet = BinaryProtocol.decode(hex(MESSAGE_131072))

        assertNotNull(packet)
        assertEquals(MessageType.MESSAGE.value, packet!!.type)
        assertArrayEquals(ByteArray(131_072), packet.payload)
    }

    @Test
    fun `v2 public message inflating one byte past 128 KiB is rejected`() {
        assertNull(BinaryProtocol.decode(hex(MESSAGE_131073)))
    }

    @Test
    fun `file transfer keeps the framed-file ceiling for the same body`() {
        // Byte-identical to MESSAGE_131073 except the type byte (0x22).
        val packet = BinaryProtocol.decode(hex(FILE_TRANSFER_131073))

        assertNotNull(packet)
        assertEquals(MessageType.FILE_TRANSFER.value, packet!!.type)
        assertArrayEquals(ByteArray(131_073), packet.payload)
    }

    @Test
    fun `declaration beyond 1032 to 1 is rejected before inflating`() {
        // Two deflate bytes (an empty final fixed-Huffman block) declaring 2,065 bytes:
        // one past 2 x 1032. No stream can produce that, so the decoder must not try.
        var calls = 0
        val packet = BinaryProtocol.decodeForTesting(hex(FILE_TRANSFER_RATIO_2065)) { data, size ->
            calls += 1
            CompressionUtil.decompress(data, size)
        }

        assertNull(packet)
        assertEquals(0, calls)
    }

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val ANNOUNCE_4096 =
            "0101070102030405060708040016101112131415161710" +
                "00edc1010d000000c2a0f74f6d0f0714000000f06e"
        const val ANNOUNCE_4097 =
            "0101070102030405060708040016101112131415161710" +
                "01edc1010d000000c2a0f74f6d0f0714000000706f"

        // Header, sender, 4-byte original size, then the 143-byte deflate body.
        val MESSAGE_131072 =
            "020207010203040506070804000000931011121314151617" +
                "00020000" +
                "edc13101000000c2a0f54fed610da0" + "00".repeat(127) + "6e"
        val MESSAGE_131073 =
            "020207010203040506070804000000931011121314151617" +
                "00020001" +
                "edc13101000000c2a0f54fed630ca0" + "00".repeat(127) + "6e"
        val FILE_TRANSFER_131073 =
            "022207010203040506070804000000931011121314151617" +
                "00020001" +
                "edc13101000000c2a0f54fed630ca0" + "00".repeat(127) + "6e"

        const val FILE_TRANSFER_RATIO_2065 =
            "022207010203040506070804000000061011121314151617" +
                "00000811" +
                "0300"
    }
}
