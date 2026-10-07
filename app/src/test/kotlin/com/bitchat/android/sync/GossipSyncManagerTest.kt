package com.bitchat.android.sync

import android.content.ContextWrapper
import android.os.Build
import com.bitchat.android.model.RequestSyncPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

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

        manager.handleRequestSync(
            "peer",
            RequestSyncPacket(
                p = 1,
                m = 1,
                data = byteArrayOf(),
                types = SyncTypeFlags.PUBLIC_MESSAGES.union(SyncTypeFlags.FRAGMENTS_AND_FILES)
            )
        )

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

        manager.handleRequestSync(
            "peer",
            RequestSyncPacket(
                p = 1,
                m = 1,
                data = byteArrayOf(),
                types = SyncTypeFlags.PUBLIC_MESSAGES.union(SyncTypeFlags.FRAGMENTS_AND_FILES)
            )
        )

        assertEquals(listOf(1, 2, 3), sent.map { it.payload.single().toInt() })
    }

    @Test
    fun `type scoped fragment request does not replay other packet classes`() {
        val sent = mutableListOf<BitchatPacket>()
        val manager = GossipSyncManager("1111222233334444", TestScope(), config)
        manager.delegate = recordingDelegate(sent)
        val now = System.currentTimeMillis()
        manager.onPublicPacketSeen(packet(MessageType.MESSAGE, now, 1))
        manager.onPublicPacketSeen(packet(MessageType.FRAGMENT, now, 2))
        manager.onPublicPacketSeen(packet(MessageType.FILE_TRANSFER, now, 3))

        manager.handleRequestSync(
            "peer",
            RequestSyncPacket(
                p = 1,
                m = 1,
                data = byteArrayOf(),
                types = SyncTypeFlags.FRAGMENT
            )
        )

        assertEquals(listOf(2), sent.map { it.payload.single().toInt() })
    }

    @Test
    fun `coverage cursor prevents replay of an identical history tail`() {
        val tinyFilterConfig = object : GossipSyncManager.ConfigProvider {
            override fun seenCapacity() = 100
            override fun gcsMaxBytes() = 1
            override fun gcsTargetFpr() = 0.01
        }
        val requester = GossipSyncManager("1111222233334444", TestScope(), tinyFilterConfig)
        val responder = GossipSyncManager("5555666677778888", TestScope(), tinyFilterConfig)
        val now = System.currentTimeMillis()
        val history = listOf(
            packet(MessageType.MESSAGE, now - 100, 1),
            packet(MessageType.MESSAGE, now - 200, 2),
            packet(MessageType.MESSAGE, now - 300, 3)
        )
        history.forEach {
            requester.onPublicPacketSeen(it)
            responder.onPublicPacketSeen(it)
        }
        val request = RequestSyncPacket.decode(
            requester.buildGcsPayload(SyncTypeFlags.PUBLIC_MESSAGES)
        )!!
        val sent = mutableListOf<BitchatPacket>()
        responder.delegate = recordingDelegate(sent)

        responder.handleRequestSync("peer", request)

        assertEquals(SyncTypeFlags.PUBLIC_MESSAGES, request.types)
        assertEquals(history.first().timestamp, request.sinceTimestamp)
        assertEquals(emptyList<Int>(), sent.map { it.payload.single().toInt() })
    }

    @Test
    fun `legacy request without type metadata remains public message only`() {
        val sent = mutableListOf<BitchatPacket>()
        val manager = GossipSyncManager("1111222233334444", TestScope(), config)
        manager.delegate = recordingDelegate(sent)
        val now = System.currentTimeMillis()
        manager.onPublicPacketSeen(packet(MessageType.MESSAGE, now, 1))
        manager.onPublicPacketSeen(packet(MessageType.FRAGMENT, now, 2))

        manager.handleRequestSync("peer", RequestSyncPacket(p = 1, m = 1, data = byteArrayOf()))

        assertEquals(listOf(1), sent.map { it.payload.single().toInt() })
    }

    @Test
    fun `public history survives manager recreation for post-reconnect sync`() {
        val filesDir = Files.createTempDirectory("gossip-sync-test-").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext() = this
            override fun getFilesDir(): File = filesDir
        }
        try {
            val now = System.currentTimeMillis()
            GossipSyncManager("1111222233334444", TestScope(), config, context)
                .onPublicPacketSeen(packet(MessageType.MESSAGE, now, 42))

            val sent = mutableListOf<BitchatPacket>()
            val restored = GossipSyncManager("1111222233334444", TestScope(), config, context)
            restored.delegate = recordingDelegate(sent)
            restored.handleRequestSync(
                "peer",
                RequestSyncPacket(
                    p = 1,
                    m = 1,
                    data = byteArrayOf(),
                    types = SyncTypeFlags.PUBLIC_MESSAGES
                )
            )

            assertEquals(listOf(42), sent.map { it.payload.single().toInt() })
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun `announcement-only request returns the latest announcement independently of message history`() {
        val sent = mutableListOf<BitchatPacket>()
        val manager = GossipSyncManager("1111222233334444", TestScope(), config)
        manager.delegate = recordingDelegate(sent)
        val now = System.currentTimeMillis()
        manager.onPublicPacketSeen(packet(MessageType.ANNOUNCE, now - 1_000, 6))
        manager.onPublicPacketSeen(packet(MessageType.MESSAGE, now, 7))

        manager.handleRequestSync(
            "peer",
            RequestSyncPacket(
                p = 1,
                m = 1,
                data = byteArrayOf(),
                types = SyncTypeFlags.ANNOUNCE
            )
        )

        assertEquals(listOf(6), sent.map { it.payload.single().toInt() })
    }

    private fun recordingDelegate(sent: MutableList<BitchatPacket>) =
        object : GossipSyncManager.Delegate {
            override fun sendPacket(packet: BitchatPacket) = Unit
            override fun sendPacketToPeer(peerID: String, packet: BitchatPacket) {
                sent += packet
            }
            override fun signPacketForBroadcast(packet: BitchatPacket) = packet
        }

    private fun packet(type: MessageType, timestamp: Long, marker: Int) = BitchatPacket(
        type = type.value,
        senderID = ByteArray(8) { 1 },
        timestamp = timestamp.toULong(),
        payload = byteArrayOf(marker.toByte()),
        ttl = 3u
    )
}
