package com.bitchat.android.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HexPeerHashingTest {

    @Test
    fun `hex encodes lowercase and decodes mixed case`() {
        val bytes = byteArrayOf(0x00, 0x0f, 0x10, 0x7f, 0xff.toByte())

        assertEquals("000f107fff", Hex.encode(bytes))
        assertArrayEquals(bytes, Hex.decode("000F107fFF"))
        assertEquals("000f107fff", bytes.toHexString())
        assertArrayEquals(bytes, "000f107fff".hexToByteArray())
    }

    @Test
    fun `hex decode rejects malformed input unless odd length is explicitly allowed`() {
        assertNull(Hex.decode("abc"))
        assertArrayEquals(byteArrayOf(0x0a, 0xbc.toByte()), Hex.decode("abc", allowOddLength = true))
        assertNull(Hex.decode("00xx"))
        assertNull("00xx".hexToByteArrayOrNull())
    }

    @Test
    fun `peer id parsing is fixed width with truncation and zero fill`() {
        assertArrayEquals(
            byteArrayOf(0x11, 0x22, 0x00, 0x44, 0, 0, 0, 0),
            PeerId.toBytes("1122zz44")
        )
        assertArrayEquals(
            byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77),
            PeerId.toBytes("00112233445566778899")
        )
        assertEquals("1122004400000000", PeerId.normalize("1122zz44"))
    }

    @Test
    fun `peer id strict parse requires exactly eight valid bytes`() {
        val bytes = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)

        assertArrayEquals(bytes, PeerId.parse("0001020304050607"))
        assertEquals("0001020304050607", PeerId.fromBytes(bytes))
        assertNull(PeerId.parse("000102"))
        assertNull(PeerId.parse("00010203040506xx"))
    }

    @Test
    fun `hashing sha256 hex matches known vector`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Hashing.sha256Hex("abc")
        )
    }
}
