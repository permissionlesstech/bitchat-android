package com.bitchat.android.sync

import android.os.Build
import com.bitchat.android.model.RequestSyncPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class GossipSyncManagerTest {
    private val config = object : GossipSyncManager.ConfigProvider {
        override fun seenCapacity() = 100
        override fun gcsMaxBytes() = 400
        override fun gcsTargetFpr() = 0.01
    }

    @Test
    fun `whole messages retain six hours while fragments retain fifteen minutes`() {
        val sent = mutableListOf<BitchatPacket>()
        val manager = GossipSyncManager("1111222233334444", TestScope(), config)
        manager.delegate = object : GossipSyncManager.Delegate {
            override fun sendPacket(packet: BitchatPacket) = Unit
            override fun sendPacketToPeer(peerID: String, packet: BitchatPacket) { sent += packet }
            override fun signPacketForBroadcast(packet: BitchatPacket) = packet
        }
        val now = System.currentTimeMillis()
        manager.onPublicPacketSeen(packet(MessageType.MESSAGE, now - 5 * 60 * 60 * 1000L, 1))
        manager.onPublicPacketSeen(packet(MessageType.MESSAGE, now - 7 * 60 * 60 * 1000L, 2))
        manager.onPublicPacketSeen(packet(MessageType.FRAGMENT, now - 10 * 60 * 1000L, 3))
        manager.onPublicPacketSeen(packet(MessageType.FRAGMENT, now - 20 * 60 * 1000L, 4))
        manager.onPublicPacketSeen(packet(MessageType.FILE_TRANSFER, now - 10 * 60 * 1000L, 5))

        manager.handleRequestSync("peer", RequestSyncPacket(p = 1, m = 1, data = byteArrayOf()))

        assertEquals(listOf(1, 3, 5), sent.map { it.payload.single().toInt() })
    }

    @Test
    fun `public history tolerates bounded future clock skew`() {
        val sent = mutableListOf<BitchatPacket>()
        val manager = GossipSyncManager("1111222233334444", TestScope(), config)
        manager.delegate = object : GossipSyncManager.Delegate {
            override fun sendPacket(packet: BitchatPacket) = Unit
            override fun sendPacketToPeer(peerID: String, packet: BitchatPacket) { sent += packet }
            override fun signPacketForBroadcast(packet: BitchatPacket) = packet
        }
        val now = System.currentTimeMillis()
        manager.onPublicPacketSeen(packet(MessageType.MESSAGE, now + 60_000L, 1))
        manager.onPublicPacketSeen(packet(MessageType.FRAGMENT, now + 60_000L, 2))
        manager.onPublicPacketSeen(packet(MessageType.FILE_TRANSFER, now + 60_000L, 3))
        manager.onPublicPacketSeen(packet(MessageType.MESSAGE, now + 11 * 60_000L, 4))
        manager.onPublicPacketSeen(packet(MessageType.FRAGMENT, now + 11 * 60_000L, 5))

        manager.handleRequestSync("peer", RequestSyncPacket(p = 1, m = 1, data = byteArrayOf()))

        assertEquals(listOf(1, 2, 3), sent.map { it.payload.single().toInt() })
    }

    private fun packet(type: MessageType, timestamp: Long, marker: Int) = BitchatPacket(
        type = type.value,
        senderID = ByteArray(8) { 1 },
        timestamp = timestamp.toULong(),
        payload = byteArrayOf(marker.toByte()),
        ttl = 3u
    )
}
