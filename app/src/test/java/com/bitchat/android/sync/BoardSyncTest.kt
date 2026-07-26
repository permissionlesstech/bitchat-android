package com.bitchat.android.sync

import com.bitchat.android.model.RequestSyncPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardSyncTest {
    @Test
    fun `board flag widens to second little-endian byte`() {
        assertArrayEquals(byteArrayOf(0, 1), SyncTypeFlags.BOARD.encode())
        val decoded = SyncTypeFlags.decode(byteArrayOf(0, 1))!!
        assertTrue(decoded.contains(MessageType.BOARD_POST))
        assertFalse(decoded.contains(MessageType.MESSAGE))
    }

    @Test
    fun `legacy one-byte flags and unknown bits remain compatible`() {
        val legacy = SyncTypeFlags.decode(byteArrayOf(0x03))!!
        assertTrue(legacy.contains(MessageType.ANNOUNCE))
        assertTrue(legacy.contains(MessageType.MESSAGE))
        assertFalse(legacy.contains(MessageType.BOARD_POST))

        val unknownOnly = SyncTypeFlags.decode(byteArrayOf(0, 0, 0x40))!!
        assertNull(unknownOnly.encode())
    }

    @Test
    fun `request sync round trips board types and cursor`() {
        val request = RequestSyncPacket(
            p = 7,
            m = 1234,
            data = byteArrayOf(1, 2, 3),
            types = SyncTypeFlags.BOARD,
            sinceTimestamp = 1_700_000_000_000uL
        )

        val decoded = RequestSyncPacket.decode(request.encode())!!

        assertEquals(7, decoded.p)
        assertEquals(1234, decoded.m)
        assertArrayEquals(byteArrayOf(1, 2, 3), decoded.data)
        assertTrue(decoded.types!!.contains(MessageType.BOARD_POST))
        assertEquals(1_700_000_000_000uL, decoded.sinceTimestamp)
    }

    @Test
    fun `legacy request without type field defaults at handler not decoder`() {
        val request = RequestSyncPacket(p = 7, m = 1, data = ByteArray(0))
        val decoded = RequestSyncPacket.decode(request.encode())!!

        assertNull(decoded.types)
        assertNull(decoded.sinceTimestamp)
    }

    @Test
    fun `board round is served only from provider`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val manager = GossipSyncManager(
            myPeerID = "0102030405060708",
            scope = scope,
            configProvider = config()
        )
        val boardPacket = packet(MessageType.BOARD_POST, timestamp = 200uL)
        val messagePacket = packet(MessageType.MESSAGE, timestamp = 300uL)
        manager.boardPacketsProvider = { listOf(boardPacket) }
        manager.onPublicPacketSeen(messagePacket)
        val sent = mutableListOf<BitchatPacket>()
        manager.delegate = object : GossipSyncManager.Delegate {
            override fun sendPacket(packet: BitchatPacket) = Unit
            override fun sendPacketToPeer(peerID: String, packet: BitchatPacket) {
                sent += packet
            }
            override fun signPacketForBroadcast(packet: BitchatPacket): BitchatPacket = packet
        }

        manager.handleRequestSync(
            fromPeerID = "1111111111111111",
            request = RequestSyncPacket(
                p = 7,
                m = 1,
                data = ByteArray(0),
                types = SyncTypeFlags.BOARD
            )
        )

        assertEquals(listOf(MessageType.BOARD_POST.value), sent.map { it.type })
        assertEquals(0u.toUByte(), sent.single().ttl)
    }

    private fun config() = object : GossipSyncManager.ConfigProvider {
        override fun seenCapacity(): Int = 500
        override fun gcsMaxBytes(): Int = 400
        override fun gcsTargetFpr(): Double = 0.01
    }

    private fun packet(type: MessageType, timestamp: ULong) = BitchatPacket(
        type = type.value,
        senderID = ByteArray(8) { 1 },
        timestamp = timestamp,
        payload = byteArrayOf(1, 2, 3),
        ttl = 7u
    )
}
