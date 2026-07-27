package com.bitchat.android.sync

import com.bitchat.android.model.RequestSyncPacket
import com.bitchat.android.protocol.MessageType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncTypeFlagsGroupTest {
    @Test
    fun `group bit ten widens little endian flags to two bytes`() {
        assertArrayEquals(
            byteArrayOf(0x00, 0x04),
            SyncTypeFlags.GROUP_MESSAGE.encoded()
        )
        assertTrue(SyncTypeFlags.decode(byteArrayOf(0x00, 0x04))!!.contains(MessageType.GROUP_MESSAGE))
    }

    @Test
    fun `unknown sync bits are ignored`() {
        val decoded = SyncTypeFlags.decode(byteArrayOf(0x00, 0x0c))!!
        assertTrue(decoded.contains(MessageType.GROUP_MESSAGE))
        assertArrayEquals(byteArrayOf(0x00, 0x04), decoded.encoded())
    }

    @Test
    fun `request sync round trips group types and legacy omission`() {
        val typed = RequestSyncPacket(5, 100, byteArrayOf(1, 2), SyncTypeFlags.GROUP_MESSAGE)
        val decoded = RequestSyncPacket.decode(typed.encode())!!
        val types = requireNotNull(decoded.types)
        assertTrue(types.contains(MessageType.GROUP_MESSAGE))
        assertFalse(types.contains(MessageType.MESSAGE))

        val legacy = RequestSyncPacket(5, 100, byteArrayOf(1, 2))
        assertTrue(RequestSyncPacket.decode(legacy.encode())!!.types == null)
    }
}
