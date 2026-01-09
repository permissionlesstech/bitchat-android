package com.bitchat.android.protocol

import org.junit.Assert.*
import org.junit.Test

class BinaryProtocolTest {

    private val senderID = hexStringToPeerBytes("1111111111111111")
    private val recipientID = hexStringToPeerBytes("2222222222222222")
    private val hop1 = hexStringToPeerBytes("aaaaaaaaaaaaaaaa")
    private val hop2 = hexStringToPeerBytes("bbbbbbbbbbbbbbbb")

    @Test
    fun `v1 packet ignores route field during encoding`() {
        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.MESSAGE.value,
            senderID = senderID,
            recipientID = recipientID,
            timestamp = 1234567890UL,
            payload = "hello".toByteArray(),
            ttl = 5u,
            route = listOf(hop1, hop2)
        )

        val encoded = BinaryProtocol.encode(packet)
        assertNotNull(encoded)

        val decoded = BinaryProtocol.decode(encoded!!)
        assertNotNull(decoded)

        // V1 should NOT have route even though we provided one
        assertEquals(1u.toUByte(), decoded!!.version)
        assertNull("V1 packet should not contain route", decoded.route)
    }

    @Test
    fun `v2 packet encodes and decodes route field`() {
        val packet = BitchatPacket(
            version = 2u,
            type = MessageType.MESSAGE.value,
            senderID = senderID,
            recipientID = recipientID,
            timestamp = 1234567890UL,
            payload = "hello".toByteArray(),
            ttl = 5u,
            route = listOf(hop1, hop2)
        )

        val encoded = BinaryProtocol.encode(packet)
        assertNotNull(encoded)

        val decoded = BinaryProtocol.decode(encoded!!)
        assertNotNull(decoded)

        // V2 should have the route
        assertEquals(2u.toUByte(), decoded!!.version)
        assertNotNull("V2 packet should contain route", decoded.route)
        assertEquals(2, decoded.route!!.size)
        assertArrayEquals(hop1, decoded.route!![0])
        assertArrayEquals(hop2, decoded.route!![1])
    }

    @Test
    fun `v2 packet without route encodes and decodes correctly`() {
        val packet = BitchatPacket(
            version = 2u,
            type = MessageType.MESSAGE.value,
            senderID = senderID,
            recipientID = recipientID,
            timestamp = 1234567890UL,
            payload = "hello".toByteArray(),
            ttl = 5u,
            route = null
        )

        val encoded = BinaryProtocol.encode(packet)
        assertNotNull(encoded)

        val decoded = BinaryProtocol.decode(encoded!!)
        assertNotNull(decoded)

        assertEquals(2u.toUByte(), decoded!!.version)
        assertNull("V2 packet with no route should decode with null route", decoded.route)
    }

    @Test
    fun `v1 packet round-trips correctly without route`() {
        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.MESSAGE.value,
            senderID = senderID,
            recipientID = recipientID,
            timestamp = 1234567890UL,
            payload = "test payload".toByteArray(),
            ttl = 3u,
            route = null
        )

        val encoded = BinaryProtocol.encode(packet)
        assertNotNull(encoded)

        val decoded = BinaryProtocol.decode(encoded!!)
        assertNotNull(decoded)

        assertEquals(1u.toUByte(), decoded!!.version)
        assertEquals(MessageType.MESSAGE.value, decoded.type)
        assertArrayEquals(senderID, decoded.senderID)
        assertArrayEquals(recipientID, decoded.recipientID)
        assertEquals(1234567890UL, decoded.timestamp)
        assertArrayEquals("test payload".toByteArray(), decoded.payload)
        assertNull(decoded.route)
    }

    @Test
    fun `v2 packet decoding logic correctly identifies route count`() {
        // Construct a packet where the first byte of senderID is a high value (e.g. 0x87 = 135)
        // If the decoder peeks at the wrong offset (the senderID), it will think route count is 135
        // and expect a huge packet size, causing decode to return null.
        val trickySenderID = hexStringToPeerBytes("872b3ccb2c3eb8c7") 
        val packet = BitchatPacket(
            version = 2u,
            type = MessageType.MESSAGE.value,
            senderID = trickySenderID,
            recipientID = recipientID,
            timestamp = 1234567890UL,
            payload = "bugcheck".toByteArray(),
            ttl = 5u,
            route = listOf(hop1) // Actual route count is 1
        )

        val encoded = BinaryProtocol.encode(packet)
        assertNotNull(encoded)

        val decoded = BinaryProtocol.decode(encoded!!)
        assertNotNull("Decoding failed - likely due to incorrect route count peek", decoded)
        
        assertEquals(2u.toUByte(), decoded!!.version)
        assertEquals(1, decoded.route!!.size)
        assertArrayEquals(trickySenderID, decoded.senderID)
    }

    private fun hexStringToPeerBytes(hex: String): ByteArray {
        val result = ByteArray(8)
        var idx = 0
        var out = 0
        while (idx + 1 < hex.length && out < 8) {
            val b = hex.substring(idx, idx + 2).toIntOrNull(16)?.toByte() ?: 0
            result[out++] = b
            idx += 2
        }
        return result
    }
}
