package com.bitchat.android.sync

import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertThrows
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GossipSyncRequestRegistryTest {

    private lateinit var scope: CoroutineScope
    private lateinit var manager: GossipSyncManager
    private val broadcasts = mutableListOf<BitchatPacket>()
    private val unicasts = mutableListOf<Pair<String, BitchatPacket>>()
    private var neighbors = listOf<String>()
    private lateinit var scheduler: TestCoroutineScheduler
    private var signingFails = false
    private var sendingFails = false

    private val delegate = object : GossipSyncManager.Delegate {
        override fun sendPacket(packet: BitchatPacket) { broadcasts += packet }
        override fun sendPacketToPeer(peerID: String, packet: BitchatPacket) {
            check(!sendingFails)
            assertTrue(manager.isValidSyncResponse(peerID))
            unicasts += peerID to packet
        }
        override fun signPacketForBroadcast(packet: BitchatPacket): BitchatPacket {
            check(!signingFails)
            return packet
        }
        override fun connectedPeerIDs(): List<String> = neighbors
    }

    private val config = object : GossipSyncManager.ConfigProvider {
        override fun seenCapacity(): Int = 100
        override fun gcsMaxBytes(): Int = 400
        override fun gcsTargetFpr(): Double = 0.01
    }

    @Before
    fun setUp() {
        scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        scope = CoroutineScope(SupervisorJob() + dispatcher)
        manager = GossipSyncManager("1122334455667788", scope, config, dispatcher) { scheduler.currentTime }
        manager.delegate = delegate
        broadcasts.clear()
        unicasts.clear()
        neighbors = emptyList()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `a request to a neighbor is open for sixty seconds and then closed`() {
        manager.noteRequestSent("AAAABBBBCCCCDDDD", nowMs = 1_000L)

        assertTrue(manager.isValidSyncResponse("aaaabbbbccccdddd", nowMs = 1_000L + 60_000L))
        assertFalse(manager.isValidSyncResponse("aaaabbbbccccdddd", nowMs = 1_000L + 60_001L))
    }

    @Test
    fun `an expired request is dropped from the registry when the next request is recorded`() {
        manager.noteRequestSent("aaaabbbbccccdddd", nowMs = 1_000L)
        manager.noteRequestSent("1111222233334444", nowMs = 1_000L + 60_001L)

        assertEquals(1, manager.openRequestCount)
        assertFalse(manager.isValidSyncResponse("aaaabbbbccccdddd", nowMs = 1_000L + 60_001L))
        assertTrue(manager.isValidSyncResponse("1111222233334444", nowMs = 1_000L + 60_001L))
    }

    @Test
    fun `an older sampled request time preserves a newer neighbor window`() {
        manager.noteRequestSent("1111222233334444", nowMs = 1_001L)
        // The other caller sampled its clock first, then resumed after this request.
        manager.noteRequestSent("aaaabbbbccccdddd", nowMs = 1_000L)

        assertTrue(manager.isValidSyncResponse("1111222233334444", nowMs = 61_001L))
        assertFalse(manager.isValidSyncResponse("1111222233334444", nowMs = 61_002L))
    }

    @Test
    fun `an older sampled request time cannot shorten the same neighbor window`() {
        manager.noteRequestSent("AAAABBBBCCCCDDDD", nowMs = 1_001L)
        manager.noteRequestSent("aaaabbbbccccdddd", nowMs = 1_000L)

        assertEquals(1, manager.openRequestCount)
        assertTrue(manager.isValidSyncResponse("aaaabbbbccccdddd", nowMs = 61_001L))
        assertFalse(manager.isValidSyncResponse("aaaabbbbccccdddd", nowMs = 61_002L))
    }

    @Test
    fun `an unrequested neighbor is not a valid response source`() {
        assertFalse(manager.isValidSyncResponse("aaaabbbbccccdddd", nowMs = 1_000L))
    }

    @Test
    fun `the periodic request goes to each known neighbor by name and opens a request for each`() {
        neighbors = listOf("aaaabbbbccccdddd", "1111222233334444")

        manager.sendPeriodicSync()

        assertEquals(2, unicasts.size)
        assertTrue(unicasts.all { it.second.type == MessageType.REQUEST_SYNC.value })
        assertEquals(neighbors.toSet(), unicasts.map { it.first }.toSet())
        assertTrue("no broadcast while neighbors are known", broadcasts.isEmpty())
        assertTrue(manager.isValidSyncResponse("aaaabbbbccccdddd"))
        assertTrue(manager.isValidSyncResponse("1111222233334444"))
    }

    @Test
    fun `with no neighbor known the periodic request is broadcast and opens nothing`() {
        manager.sendPeriodicSync()

        assertEquals(1, broadcasts.size)
        assertEquals(MessageType.REQUEST_SYNC.value, broadcasts[0].type)
        assertTrue(unicasts.isEmpty())
        assertFalse(manager.isValidSyncResponse("aaaabbbbccccdddd"))
    }

    @Test
    fun `periodic timer sends a targeted request after thirty seconds`() {
        neighbors = listOf("aaaabbbbccccdddd")
        manager.start()
        scheduler.runCurrent()
        scheduler.advanceTimeBy(30_000)
        scheduler.runCurrent()
        assertEquals(1, unicasts.size)
        assertTrue(manager.isValidSyncResponse(neighbors.single()))
        assertTrue(broadcasts.isEmpty())
        manager.stop()
    }

    @Test
    fun `scheduled initial request opens a window for its target`() {
        manager.scheduleInitialSyncToPeer("aaaabbbbccccdddd", 1_000)
        scheduler.advanceTimeBy(999)
        assertFalse(manager.isValidSyncResponse("aaaabbbbccccdddd"))
        scheduler.advanceTimeBy(1)
        scheduler.runCurrent()
        assertEquals(1, unicasts.size)
        assertTrue(manager.isValidSyncResponse("aaaabbbbccccdddd"))
        scheduler.advanceTimeBy(60_001)
        assertFalse(manager.isValidSyncResponse("aaaabbbbccccdddd"))
    }

    @Test
    fun `initial sync uses known neighbors instead of an untracked broadcast`() {
        neighbors = listOf("aaaabbbbccccdddd")
        manager.scheduleInitialSync(0)
        scheduler.runCurrent()
        assertEquals(1, unicasts.size)
        assertTrue(broadcasts.isEmpty())
    }

    @Test
    fun `no delegate or failed signing leaves no open request`() {
        neighbors = listOf("aaaabbbbccccdddd")
        signingFails = true
        assertThrows(IllegalStateException::class.java) { manager.sendPeriodicSync() }
        assertFalse(manager.isValidSyncResponse(neighbors.single()))
        manager.delegate = null
        manager.scheduleInitialSyncToPeer(neighbors.single(), 0)
        scheduler.runCurrent()
        assertFalse(manager.isValidSyncResponse(neighbors.single()))
    }

    @Test
    fun `a throwing send removes the request window`() {
        neighbors = listOf("aaaabbbbccccdddd")
        sendingFails = true
        assertThrows(IllegalStateException::class.java) { manager.sendPeriodicSync() }
        assertFalse(manager.isValidSyncResponse(neighbors.single()))
    }

    @Test
    fun `an earlier failed send preserves a later request at the same clock time`() {
        neighbors = listOf("aaaabbbbccccdddd")
        var firstSend = true
        manager.delegate = object : GossipSyncManager.Delegate by delegate {
            override fun sendPacketToPeer(peerID: String, packet: BitchatPacket) {
                if (firstSend) {
                    firstSend = false
                    manager.sendPeriodicSync()
                    throw IllegalStateException("synthetic earlier send failure")
                }
            }
        }

        assertThrows(IllegalStateException::class.java) { manager.sendPeriodicSync() }
        assertTrue(manager.isValidSyncResponse(neighbors.single()))
    }

    @Test
    fun `a failed retry preserves the previous successful request window`() {
        neighbors = listOf("aaaabbbbccccdddd")
        manager.sendPeriodicSync()
        scheduler.advanceTimeBy(1_000)
        sendingFails = true

        assertThrows(IllegalStateException::class.java) { manager.sendPeriodicSync() }
        assertTrue(manager.isValidSyncResponse(neighbors.single(), nowMs = 60_000))
        assertFalse(manager.isValidSyncResponse(neighbors.single(), nowMs = 60_001))
    }

    @Test
    fun `failed overlapping sends leave no request window`() {
        neighbors = listOf("aaaabbbbccccdddd")
        var firstSend = true
        manager.delegate = object : GossipSyncManager.Delegate by delegate {
            override fun sendPacketToPeer(peerID: String, packet: BitchatPacket) {
                if (firstSend) {
                    firstSend = false
                    assertThrows(IllegalStateException::class.java) { manager.sendPeriodicSync() }
                }
                throw IllegalStateException("synthetic send failure")
            }
        }

        assertThrows(IllegalStateException::class.java) { manager.sendPeriodicSync() }
        assertFalse(manager.isValidSyncResponse(neighbors.single()))
    }

    @Test
    fun `an older sampled successful send survives a newer send failure`() {
        val sampled = CountDownLatch(1)
        val resumeOlder = CountDownLatch(1)
        val clockCalls = AtomicInteger()
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "older-request") }
        lateinit var olderSend: Future<*>
        neighbors = listOf("aaaabbbbccccdddd")
        manager = GossipSyncManager("1122334455667788", scope, config, Dispatchers.Unconfined) {
            if (clockCalls.getAndIncrement() == 0) {
                sampled.countDown()
                check(resumeOlder.await(5, TimeUnit.SECONDS))
                1_000L
            } else 1_001L
        }
        manager.delegate = object : GossipSyncManager.Delegate by delegate {
            override fun sendPacketToPeer(peerID: String, packet: BitchatPacket) {
                if (Thread.currentThread().name != "older-request") {
                    resumeOlder.countDown()
                    olderSend.get(5, TimeUnit.SECONDS)
                    throw IllegalStateException("synthetic newer send failure")
                }
            }
        }
        try {
            olderSend = executor.submit { manager.sendPeriodicSync() }
            assertTrue(sampled.await(5, TimeUnit.SECONDS))
            assertThrows(IllegalStateException::class.java) { manager.sendPeriodicSync() }
            assertTrue(manager.isValidSyncResponse(neighbors.single(), nowMs = 61_000))
            assertFalse(manager.isValidSyncResponse(neighbors.single(), nowMs = 61_001))
        } finally {
            resumeOlder.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `clearing gossip also clears request windows`() {
        neighbors = listOf("aaaabbbbccccdddd")
        manager.sendPeriodicSync()
        manager.clear()
        assertFalse(manager.isValidSyncResponse(neighbors.single()))
    }

    @Test
    fun `a response cannot precede its request on the elapsed clock`() {
        manager.noteRequestSent("aaaabbbbccccdddd", nowMs = 1_000)
        assertFalse(manager.isValidSyncResponse("aaaabbbbccccdddd", nowMs = 999))
    }
}
