package com.bitchat.android.mesh

import com.bitchat.android.protocol.BinaryProtocol
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.CompressionUtil
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.services.meshgraph.MeshGraphService
import com.bitchat.android.services.meshgraph.RoutePlanner
import com.bitchat.android.sync.GCSFilter
import com.bitchat.android.sync.GossipSyncManager
import com.bitchat.android.ui.debug.DebugSettingsManager
import com.bitchat.android.util.dataFromHexString
import com.bitchat.android.util.toHexString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class BatteryOptimizationContractTest {

    @Test
    fun `fast hex encoding and decoding match standard formats`() {
        val bytes = ByteArray(256) { it.toByte() }
        val hex = bytes.toHexString()
        assertEquals(512, hex.length)
        assertEquals("00010203", bytes.toHexString(4))
        assertArrayEquals(bytes, hex.dataFromHexString())
        assertArrayEquals(bytes, "  0x${hex.uppercase()}  ".dataFromHexString())
        assertNull("abc".dataFromHexString())
        assertNull("zz".dataFromHexString())
        assertArrayEquals(byteArrayOf(0x01, 0x23, 0x45, 0x67, 0x89.toByte(), 0xab.toByte(), 0xcd.toByte(), 0xef.toByte()),
            MeshPacketUtils.hexStringToByteArray("0123456789abcdef"))
    }

    @Test
    fun `zero-alloc compression entropy check matches threshold behavior`() {
        // Too small
        assertFalse(CompressionUtil.shouldCompress(ByteArray(50) { 0x41 }))
        // Highly repetitive -> should compress
        assertTrue(CompressionUtil.shouldCompress(ByteArray(500) { (it % 10).toByte() }))
        // All 256 unique bytes -> high entropy -> should not compress
        assertFalse(CompressionUtil.shouldCompress(ByteArray(512) { (it and 0xFF).toByte() }))
        // Exactly 90% unique out of 200 -> 180 unique -> 0.9 -> false
        val exactly90 = ByteArray(200) { idx -> if (idx < 180) idx.toByte() else 0 }
        assertFalse(CompressionUtil.shouldCompress(exactly90))
        // 179 unique out of 200 -> 0.895 < 0.9 -> true
        val below90 = ByteArray(200) { idx -> if (idx < 179) idx.toByte() else 0 }
        assertTrue(CompressionUtil.shouldCompress(below90))
    }

    @Test
    fun `single-allocation BinaryProtocol encode round-trips v1 and v2 routed packets`() {
        val sender = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val recipient = byteArrayOf(9, 10, 11, 12, 13, 14, 15, 16)
        val hop = byteArrayOf(21, 22, 23, 24, 25, 26, 27, 28)
        val sig = ByteArray(64) { (it * 3).toByte() }
        val payload = "hello mesh optimization test ".repeat(10).toByteArray()

        val v2 = BitchatPacket(
            version = 2u,
            type = MessageType.MESSAGE.value,
            senderID = sender,
            recipientID = recipient,
            timestamp = 1_700_000_000_000uL,
            payload = payload,
            signature = sig,
            ttl = 7u,
            route = listOf(hop)
        )
        val encodedPadded = BinaryProtocol.encode(v2, padding = true)
        assertNotNull(encodedPadded)
        val decoded = BinaryProtocol.decode(encodedPadded!!)
        assertNotNull(decoded)
        assertEquals(v2, decoded)

        val encodedUnpadded = BinaryProtocol.encode(v2, padding = false)
        assertNotNull(encodedUnpadded)
        assertEquals(v2, BinaryProtocol.decode(encodedUnpadded!!))
    }

    @Test
    fun `BinaryProtocol padded v2 encode matches literal golden wire vector`() {
        val packet = BitchatPacket(
            version = 2u,
            type = MessageType.MESSAGE.value, // 0x02
            senderID = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08),
            recipientID = null,
            timestamp = 0x0000018B_00000001uL,
            payload = byteArrayOf(0x41, 0x42, 0x43, 0x44), // "ABCD"
            signature = null,
            ttl = 5u
        )

        val encoded = BinaryProtocol.encode(packet, padding = true)
        assertNotNull(encoded)
        // Raw v2 size = 16 (header) + 8 (senderID) + 4 (payload) = 28 bytes.
        // MessagePadding.optimalBlockSize(28) pads to 256 bytes with 228 bytes of 0xE4 (PKCS#7).
        assertEquals(256, encoded!!.size)
        val expectedHeaderAndBody = byteArrayOf(
            // version=2, type=0x02, ttl=5
            0x02, 0x02, 0x05,
            // timestamp big-endian 0x0000018B00000001
            0x00, 0x00, 0x01, 0x8B.toByte(), 0x00, 0x00, 0x00, 0x01,
            // flags=0x00 (no recipient, no signature, uncompressed, no route)
            0x00,
            // v2 4-byte payload length = 4
            0x00, 0x00, 0x00, 0x04,
            // senderID
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
            // payload "ABCD"
            0x41, 0x42, 0x43, 0x44
        )
        val expectedFull = ByteArray(256) { idx ->
            if (idx < expectedHeaderAndBody.size) expectedHeaderAndBody[idx] else 228.toByte()
        }
        assertArrayEquals(expectedFull, encoded)
        assertEquals(packet, BinaryProtocol.decode(encoded))
    }

    @Test
    fun `PeerManager startup cleanup does not crash under immediately executing dispatcher`() {
        val peerManager = PeerManager(cleanupDispatcher = Dispatchers.Unconfined)
        try {
            assertEquals(0, peerManager.getActivePeerCount())
            peerManager.updatePeerLastSeen("0102030405060708")
        } finally {
            peerManager.shutdown()
        }
    }

    @Test
    fun `GossipSyncManager foreground transition cancels long background delay immediately`() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val intervalFlow = MutableStateFlow(300_000L)
        val sentCount = AtomicInteger(0)

        val gossip = GossipSyncManager(
            myPeerID = "0102030405060708",
            scope = scope,
            configProvider = object : GossipSyncManager.ConfigProvider {
                override fun seenCapacity(): Int = 100
                override fun gcsMaxBytes(): Int = 256
                override fun gcsTargetFpr(): Double = 0.01
            },
            hasPeersProvider = { true },
            syncIntervalProvider = { intervalFlow.value },
            ioDispatcher = dispatcher,
            syncIntervalFlow = intervalFlow
        )
        gossip.delegate = object : GossipSyncManager.Delegate {
            override fun sendPacket(packet: BitchatPacket) { sentCount.incrementAndGet() }
            override fun sendPacketToPeer(peerID: String, packet: BitchatPacket) { sentCount.incrementAndGet() }
            override fun signPacketForBroadcast(packet: BitchatPacket): BitchatPacket = packet
        }

        gossip.start()
        // Advance 10s into the 300s background sleep -> no sync yet
        scope.advanceTimeBy(10_000L)
        scope.runCurrent()
        assertEquals(0, sentCount.get())

        // Return to foreground -> switches immediately to 30s cadence without waiting out remaining 290s
        intervalFlow.value = GossipSyncManager.DEFAULT_SYNC_INTERVAL_MS
        scope.runCurrent()
        scope.advanceTimeBy(GossipSyncManager.DEFAULT_SYNC_INTERVAL_MS + 1)
        scope.runCurrent()
        assertEquals(1, sentCount.get())

        gossip.stop()
    }

    @Test
    fun `FragmentManager zero-boxing reassembly preserves payload bytes`() {
        val manager = FragmentManager()
        try {
            val largePayload = ByteArray(2500) { (it * 31).toByte() }
            val original = BitchatPacket(
                version = 1u,
                type = MessageType.FILE_TRANSFER.value,
                senderID = MeshPacketUtils.hexStringToByteArray("0102030405060708"),
                timestamp = 1_700_000_000_000uL,
                payload = largePayload,
                ttl = 5u
            )
            val fragments = manager.createFragments(original)
            assertTrue("expected multiple fragments", fragments.size > 1)
            var reassembled: BitchatPacket? = null
            for (frag in fragments) {
                reassembled = manager.handleFragment(frag)
            }
            assertNotNull(reassembled)
            assertArrayEquals(largePayload, reassembled!!.payload)
            assertEquals(0u.toUByte(), reassembled.ttl)
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun `GossipSyncManager skips periodic sync broadcasts when no peers are connected`() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val hasPeers = AtomicBoolean(false)
        val sentCount = AtomicInteger(0)
        val signCount = AtomicInteger(0)

        val gossip = GossipSyncManager(
            myPeerID = "0102030405060708",
            scope = scope,
            configProvider = object : GossipSyncManager.ConfigProvider {
                override fun seenCapacity(): Int = 100
                override fun gcsMaxBytes(): Int = 256
                override fun gcsTargetFpr(): Double = 0.01
            },
            hasPeersProvider = { hasPeers.get() },
            syncIntervalProvider = { 5_000L },
            ioDispatcher = dispatcher
        )
        gossip.delegate = object : GossipSyncManager.Delegate {
            override fun sendPacket(packet: BitchatPacket) { sentCount.incrementAndGet() }
            override fun sendPacketToPeer(peerID: String, packet: BitchatPacket) { sentCount.incrementAndGet() }
            override fun signPacketForBroadcast(packet: BitchatPacket): BitchatPacket {
                signCount.incrementAndGet()
                return packet
            }
        }

        gossip.start()
        // Wait 60 seconds with 0 peers -> 0 signs and 0 broadcasts!
        scope.advanceTimeBy(60_000L)
        scope.runCurrent()
        assertEquals(0, signCount.get())
        assertEquals(0, sentCount.get())

        // Once a peer connects, sync resumes on the next interval.
        hasPeers.set(true)
        scope.advanceTimeBy(6_000L)
        scope.runCurrent()
        assertTrue(signCount.get() >= 1)
        assertTrue(sentCount.get() >= 1)

        gossip.stop()
    }

    @Test
    fun `GCSFilter precomputed hashes produce identical membership results`() {
        val ids = List(200) { idx ->
            ByteArray(16) { b -> ((idx * 17 + b) and 0xFF).toByte() }
        }
        val params = GCSFilter.buildFilter(ids, maxBytes = 128, targetFpr = 0.01)
        assertTrue(params.data.size <= 128)
        val sorted = GCSFilter.decodeToSortedSet(params.p, params.m, params.data)
        val encodedCount = (params.m ushr params.p).toInt().coerceAtMost(ids.size)
        for (i in 0 until encodedCount) {
            val v = GCSFilter.h64(ids[i]) % params.m
            val nonZero = if (v == 0L) 1L else v
            assertTrue("encoded id $i must be present", GCSFilter.contains(sorted, nonZero))
        }
    }

    @Test
    fun `MeshGraphService skips snapshot rebuild on unchanged announces and RoutePlanner BFS finds shortest path`() {
        MeshGraphService.resetForTesting()
        val graph = MeshGraphService.getInstance()
        graph.updateFromAnnouncement("a", "Alice", listOf("b"), 100uL)
        graph.updateFromAnnouncement("b", "Bob", listOf("a", "c"), 100uL)
        graph.updateFromAnnouncement("c", "Carol", listOf("b"), 100uL)

        val snapshotBefore = graph.graphState.value
        // Newer timestamp, identical nickname and neighbors -> should keep the exact same snapshot instance
        graph.updateFromAnnouncement("b", "Bob", listOf("a", "c"), 200uL)
        assertTrue(snapshotBefore === graph.graphState.value)

        val path = RoutePlanner.shortestPath("a", "c")
        assertEquals(listOf("a", "b", "c"), path)
        MeshGraphService.resetForTesting()
    }

    @Test
    fun `DebugSettingsManager tracks atomic totals without mutating UI flows while sheet is hidden`() {
        val dbg = DebugSettingsManager.getInstance()
        dbg.setDebugSheetVisible(false)
        val beforeFlowCount = dbg.relayStats.value.totalIncomingCount
        val pkt = BitchatPacket(
            version = 1u,
            type = MessageType.MESSAGE.value,
            senderID = MeshPacketUtils.hexStringToByteArray("1122334455667788"),
            timestamp = 1_700_000_000_000uL,
            payload = byteArrayOf(1, 2, 3),
            ttl = 7u
        )
        repeat(5) {
            dbg.logIncoming(pkt, "1122334455667788", "peer", "AA:BB:CC:DD:EE:FF", "0102030405060708")
        }
        // Flow was not churned while hidden
        assertEquals(beforeFlowCount, dbg.relayStats.value.totalIncomingCount)
        // Opening debug sheet snapshots accurate totals immediately
        dbg.setDebugSheetVisible(true)
        assertTrue(dbg.relayStats.value.totalIncomingCount >= beforeFlowCount + 5)
        dbg.setDebugSheetVisible(false)
    }
}
