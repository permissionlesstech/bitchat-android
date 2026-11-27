package com.bitchat.android.sync

import com.bitchat.android.mesh.PowerProfileResolver
import com.bitchat.android.model.RequestSyncPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.util.AppConstants
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
class GossipSyncScheduleTest {

    @Test
    fun `foreground transition preserves elapsed time and keeps foreground cadence responsive`() {
        val fgLow = PowerProfileResolver.resolve(batteryLevel = 15, isCharging = false, isBackground = false, hasDirectPeers = true)
        val bgNormal = PowerProfileResolver.resolve(batteryLevel = 80, isCharging = false, isBackground = true, hasDirectPeers = true)
        val bgCritical = PowerProfileResolver.resolve(batteryLevel = 5, isCharging = false, isBackground = true, hasDirectPeers = true)
        assertEquals(GossipSyncManager.FOREGROUND_LOW_BATTERY_SYNC_INTERVAL_MS, GossipSyncManager.recommendedSyncIntervalMs(fgLow))
        assertEquals(180_000L, GossipSyncManager.recommendedSyncIntervalMs(bgNormal))
        assertEquals(300_000L, GossipSyncManager.recommendedSyncIntervalMs(bgCritical))

        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val intervalFlow = MutableStateFlow(300_000L)
        val sentCount = AtomicInteger(0)

        val gossip = GossipSyncManager(
            myPeerID = "0102030405060708",
            scope = scope,
            configProvider = defaultConfig(),
            hasPeersProvider = { true },
            syncIntervalProvider = { intervalFlow.value },
            ioDispatcher = dispatcher,
            syncIntervalFlow = intervalFlow,
            monotonicClockMs = { scope.testScheduler.currentTime }
        )
        gossip.delegate = object : GossipSyncManager.Delegate {
            override fun sendPacket(packet: BitchatPacket) { sentCount.incrementAndGet() }
            override fun sendPacketToPeer(peerID: String, packet: BitchatPacket) { sentCount.incrementAndGet() }
            override fun signPacketForBroadcast(packet: BitchatPacket): BitchatPacket = packet
        }

        gossip.start()
        scope.advanceTimeBy(20_000L)
        scope.runCurrent()
        assertEquals(0, sentCount.get())

        // Return to foreground (30s cadence): only 10s remaining (not a restarted full 30s)
        intervalFlow.value = GossipSyncManager.DEFAULT_SYNC_INTERVAL_MS
        scope.runCurrent()
        scope.advanceTimeBy(10_001L)
        scope.runCurrent()
        assertEquals(1, sentCount.get())

        gossip.stop()
    }

    @Test
    fun `periodic sync broadcasts are skipped when no peers are connected`() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val hasPeers = AtomicBoolean(false)
        val sentCount = AtomicInteger(0)
        val signCount = AtomicInteger(0)

        val gossip = GossipSyncManager(
            myPeerID = "0102030405060708",
            scope = scope,
            configProvider = defaultConfig(),
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
        scope.advanceTimeBy(60_000L)
        scope.runCurrent()
        assertEquals(0, signCount.get())
        assertEquals(0, sentCount.get())

        hasPeers.set(true)
        scope.advanceTimeBy(6_000L)
        scope.runCurrent()
        assertTrue(signCount.get() >= 1)
        assertTrue(sentCount.get() >= 1)

        gossip.stop()
    }

    @Test
    fun `clear removes cached packets and expired announcements are excluded from sync responses`() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val nowMs = AtomicLong(1_700_000_000_000L)
        val sent = mutableListOf<BitchatPacket>()

        val gossip = GossipSyncManager(
            myPeerID = "0102030405060708",
            scope = scope,
            configProvider = defaultConfig(),
            ioDispatcher = dispatcher,
            wallClockMs = { nowMs.get() }
        )
        gossip.delegate = object : GossipSyncManager.Delegate {
            override fun sendPacket(packet: BitchatPacket) = Unit
            override fun sendPacketToPeer(peerID: String, packet: BitchatPacket) { sent.add(packet) }
            override fun signPacketForBroadcast(packet: BitchatPacket): BitchatPacket = packet
        }

        val sender = ByteArray(8) { 0x42 }
        val announce = BitchatPacket(
            type = MessageType.ANNOUNCE.value,
            senderID = sender,
            timestamp = nowMs.get().toULong(),
            payload = byteArrayOf(1, 2, 3),
            ttl = 1u
        )
        gossip.onPublicPacketSeen(announce)

        // Fresh announcement is included in response to empty filter (warms snapshot cache)
        gossip.handleRequestSync("5555666677778888", RequestSyncPacket(8, 1, ByteArray(0)))
        scope.advanceUntilIdle()
        assertEquals(1, sent.size)

        // Advance wall clock past STALE_PEER_TIMEOUT_MS WITHOUT running periodic cleanup:
        // expired announcement must be excluded even though cached snapshot existed
        sent.clear()
        nowMs.addAndGet(AppConstants.Mesh.STALE_PEER_TIMEOUT_MS + 1L)
        gossip.handleRequestSync("5555666677778888", RequestSyncPacket(8, 1, ByteArray(0)))
        scope.advanceUntilIdle()
        assertTrue("expired announcement must not enter sync snapshot", sent.isEmpty())

        // Panic clear drops cached snapshot references cleanly
        val freshAnnounce = announce.copy(timestamp = nowMs.get().toULong())
        gossip.onPublicPacketSeen(freshAnnounce)
        gossip.handleRequestSync("5555666677778888", RequestSyncPacket(8, 1, ByteArray(0)))
        scope.advanceUntilIdle()
        assertEquals(1, sent.size)

        sent.clear()
        gossip.clear()
        gossip.handleRequestSync("5555666677778888", RequestSyncPacket(8, 1, ByteArray(0)))
        scope.advanceUntilIdle()
        assertTrue("cleared gossip manager must not serve cached pre-wipe packets", sent.isEmpty())
    }

    private fun defaultConfig() = object : GossipSyncManager.ConfigProvider {
        override fun seenCapacity(): Int = 100
        override fun gcsMaxBytes(): Int = 256
        override fun gcsTargetFpr(): Double = 0.01
    }
}
