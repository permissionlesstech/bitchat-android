package com.bitchat.android.sync

import com.bitchat.android.protocol.MessageType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncTypeFlagsTest {
    @Test
    fun `wire encoding uses compact little endian iOS bit positions`() {
        val flags = SyncTypeFlags.PUBLIC_MESSAGES.union(SyncTypeFlags.FRAGMENTS_AND_FILES)

        assertArrayEquals(byteArrayOf(0xa3.toByte()), flags.encode())
        assertEquals(flags, SyncTypeFlags.decode(byteArrayOf(0xa3.toByte())))
        assertTrue(flags.contains(MessageType.ANNOUNCE))
        assertTrue(flags.contains(MessageType.FILE_TRANSFER))
        assertFalse(flags.contains(MessageType.NOISE_HANDSHAKE))
    }

    @Test
    fun `unknown extended bits are normalized away`() {
        val decoded = SyncTypeFlags.decode(byteArrayOf(0x03, 0xfc.toByte()))!!

        assertEquals(SyncTypeFlags.PUBLIC_MESSAGES, decoded)
        assertArrayEquals(byteArrayOf(0x03), decoded.encode())
    }

    @Test
    fun `empty and oversized fields are rejected`() {
        assertNull(SyncTypeFlags.decode(byteArrayOf()))
        assertNull(SyncTypeFlags.decode(ByteArray(9)))
    }
}
