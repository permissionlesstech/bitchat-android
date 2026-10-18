package com.bitchat.android.protocol

import com.bitchat.android.model.RequestSyncPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestSyncPacketTest {

    @Test
    fun testBaseFieldsRoundTrip() {
        val original = RequestSyncPacket(
            p = 7,
            m = 12800L,
            data = byteArrayOf(1, 2, 3, 4, 5)
        )
        val encoded = original.encode()
        val decoded = RequestSyncPacket.decode(encoded)

        assertNotNull(decoded)
        assertEquals(7, decoded!!.p)
        assertEquals(12800L, decoded.m)
        assertTrue(original.data.contentEquals(decoded.data))
        assertNull(decoded.wantedTypes)
        assertNull(decoded.minTimestamp)
    }

    @Test
    fun testUpgradedFieldsRoundTrip() {
        val original = RequestSyncPacket(
            p = 8,
            m = 25600L,
            data = byteArrayOf(10, 20, 30),
            wantedTypes = listOf(0x01u, 0x02u),
            minTimestamp = 1700000000000uL
        )
        val encoded = original.encode()
        val decoded = RequestSyncPacket.decode(encoded)

        assertNotNull(decoded)
        assertEquals(8, decoded!!.p)
        assertEquals(25600L, decoded.m)
        assertTrue(original.data.contentEquals(decoded.data))
        assertNotNull(decoded.wantedTypes)
        assertEquals(2, decoded.wantedTypes!!.size)
        assertEquals(0x01u.toUByte(), decoded.wantedTypes!![0])
        assertEquals(0x02u.toUByte(), decoded.wantedTypes!![1])
        assertEquals(1700000000000uL, decoded.minTimestamp)
    }

    @Test
    fun testLegacyCompatibleDecode() {
        // Construct a raw legacy payload manually without fields 0x04 or 0x05
        // Payload consists of:
        // Type 0x01: length 1, value P (7)
        // Type 0x02: length 4, value M (12800 -> 0x00003200)
        // Type 0x03: length 3, value data (1, 2, 3)
        val payload = byteArrayOf(
            0x01, 0x00, 0x01, 0x07, // P
            0x02, 0x00, 0x04, 0x00, 0x00, 0x32, 0x00, // M
            0x03, 0x00, 0x03, 0x01, 0x02, 0x03 // data
        )
        
        val decoded = RequestSyncPacket.decode(payload)
        assertNotNull(decoded)
        assertEquals(7, decoded!!.p)
        assertEquals(12800L, decoded.m)
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(decoded.data))
        assertNull(decoded.wantedTypes)
        assertNull(decoded.minTimestamp)
    }
}
