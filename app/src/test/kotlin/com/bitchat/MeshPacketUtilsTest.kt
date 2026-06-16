package com.bitchat

import com.bitchat.android.mesh.MeshPacketUtils
import junit.framework.TestCase.assertEquals
import org.junit.Test

class MeshPacketUtilsTest {
    @Test
    fun hexStringToByteArray_parsesFullId() {
        val bytes = MeshPacketUtils.hexStringToByteArray("0011223344556677")
        assertEquals(8, bytes.size)
        assertEquals(0x00.toByte(), bytes[0])
        assertEquals(0x11.toByte(), bytes[1])
        assertEquals(0x22.toByte(), bytes[2])
        assertEquals(0x33.toByte(), bytes[3])
        assertEquals(0x44.toByte(), bytes[4])
        assertEquals(0x55.toByte(), bytes[5])
        assertEquals(0x66.toByte(), bytes[6])
        assertEquals(0x77.toByte(), bytes[7])
    }

    @Test
    fun hexStringToByteArray_parsesShortId() {
        val bytes = MeshPacketUtils.hexStringToByteArray("ab")
        assertEquals(8, bytes.size)
        assertEquals(0xab.toByte(), bytes[0])
        assertEquals(0x00.toByte(), bytes[1])
    }

    @Test
    fun hexStringToByteArray_handlesInvalidHex() {
        val bytes = MeshPacketUtils.hexStringToByteArray("zz")
        assertEquals(8, bytes.size)
        assertEquals(0x00.toByte(), bytes[0])
    }

    @Test
    fun sha256Hex_matchesKnownValue() {
        val hash = MeshPacketUtils.sha256Hex("hello".toByteArray())
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            hash
        )
    }
}
