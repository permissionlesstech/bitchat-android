package com.bitchat.android.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HexStringTest {

    @Test
    fun `decodes even-length lowercase hex`() {
        assertArrayEquals(
            byteArrayOf(0x0a, 0xbc.toByte()),
            "0abc".dataFromHexString()
        )
    }

    @Test
    fun `rejects odd-length hex instead of truncating`() {
        // Previously length/2 silently dropped the trailing nibble.
        assertNull("abc".dataFromHexString())
        assertNull("0ab".dataFromHexString())
    }

    @Test
    fun `strips optional 0x prefix like iOS`() {
        assertArrayEquals(
            byteArrayOf(0xde.toByte(), 0xad.toByte()),
            "0xdead".dataFromHexString()
        )
        assertArrayEquals(
            byteArrayOf(0xbe.toByte(), 0xef.toByte()),
            "0Xbeef".dataFromHexString()
        )
    }

    @Test
    fun `empty string and empty after prefix yield empty array`() {
        assertArrayEquals(ByteArray(0), "".dataFromHexString())
        assertArrayEquals(ByteArray(0), "0x".dataFromHexString())
        assertTrue("  ".dataFromHexString()!!.isEmpty())
    }

    @Test
    fun `rejects non-hex characters`() {
        assertNull("zz".dataFromHexString())
        assertNull("0xgg".dataFromHexString())
    }

    @Test
    fun `round-trips with hexEncodedString`() {
        val original = byteArrayOf(0x00, 0x7f, 0xff.toByte())
        val encoded = original.hexEncodedString()
        assertEquals("007fff", encoded)
        assertArrayEquals(original, encoded.dataFromHexString())
    }
}
