package com.bitchat.android.mesh

import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The per-peer actor map is keyed by an ID read straight off the wire and was
 * only ever emptied at shutdown, so it grew for the life of the process. It
 * was also a plain map mutated from the BLE callback threads, which meant the
 * one-actor-per-peer property this class exists to provide was not actually
 * guaranteed.
 */
class PacketProcessorActorBoundsTest {
    private val processors = mutableListOf<PacketProcessor>()

    @After
    fun tearDown() {
        processors.forEach(PacketProcessor::shutdown)
    }

    @Test
    fun `actor count stays at the cap however many peers are seen`() {
        val processor = processor()

        // Peer IDs are ephemeral and rotate, and nothing stops a peer in radio
        // range from minting new ones, so "distinct senders" is unbounded.
        repeat(PacketProcessor.MAX_PEER_ACTORS + 200) { index ->
            processor.processPacket(packetFrom(peerID(index)))
        }

        assertEquals(PacketProcessor.MAX_PEER_ACTORS, processor.activePeerActorCount)
    }

    @Test
    fun `a peer still being used is not evicted by a burst of new ones`() {
        val processor = processor()
        val busy = peerID(0)

        processor.processPacket(packetFrom(busy))
        repeat(PacketProcessor.MAX_PEER_ACTORS * 2) { index ->
            processor.processPacket(packetFrom(peerID(index + 1)))
            // Keep touching the long-lived peer. Evicting by insertion order
            // would drop it anyway; access order is what saves it.
            processor.processPacket(packetFrom(busy))
        }

        assertEquals(PacketProcessor.MAX_PEER_ACTORS, processor.activePeerActorCount)
        assertTrue(
            "The peer in active use must survive eviction",
            processor.getDebugInfo().contains(busy)
        )
    }

    @Test
    fun `concurrent packets from one peer create exactly one actor`() {
        val processor = processor()
        val peer = peerID(7)
        val threads = 16
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        repeat(threads) {
            pool.execute {
                start.await()
                repeat(50) { processor.processPacket(packetFrom(peer)) }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdown()

        // Two actors for one peer would process that peer's packets in
        // parallel — exactly the session race the actors were introduced to
        // stop. Timing-dependent, so this locks the fix in rather than
        // reproducing the failure on demand.
        assertEquals(1, processor.activePeerActorCount)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `peer churn cannot overlap packets already being processed`() = runTest {
        val peer = peerID(0)
        val releaseFirst = CompletableDeferred<Unit>()
        var handled = 0
        val processor = PacketProcessor(MY_PEER_ID, StandardTestDispatcher(testScheduler))
        processors += processor
        processor.delegate = object : NoopDelegate() {
            override suspend fun handleAnnounce(routed: RoutedPacket): Boolean {
                if (routed.peerID == peer) {
                    handled++
                    if (handled == 1) releaseFirst.await()
                }
                return true
            }
        }
        processor.processPacket(packetFrom(peer, MessageType.ANNOUNCE))
        runCurrent()
        assertEquals(1, handled)
        repeat(PacketProcessor.MAX_PEER_ACTORS) { index ->
            processor.processPacket(packetFrom(peerID(index + 1), MessageType.ANNOUNCE))
        }
        runCurrent()
        processor.processPacket(packetFrom(peer, MessageType.ANNOUNCE))
        runCurrent()
        assertEquals("The second packet must wait for the first", 1, handled)
        releaseFirst.complete(Unit)
        runCurrent()
        assertEquals(2, handled)
        assertTrue(processor.activePeerActorCount <= PacketProcessor.MAX_PEER_ACTORS)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a full busy pool admits a new peer after a slot becomes idle`() = runTest {
        val newcomer = peerID(PacketProcessor.MAX_PEER_ACTORS + 1)
        var newcomerPackets = 0
        val processor = PacketProcessor(MY_PEER_ID, StandardTestDispatcher(testScheduler))
        processors += processor
        processor.delegate = object : NoopDelegate() {
            override suspend fun handleAnnounce(routed: RoutedPacket): Boolean {
                if (routed.peerID == newcomer) newcomerPackets++
                return true
            }
        }
        repeat(PacketProcessor.MAX_PEER_ACTORS) { index ->
            processor.processPacket(packetFrom(peerID(index), MessageType.ANNOUNCE))
        }
        processor.processPacket(packetFrom(newcomer, MessageType.ANNOUNCE))
        runCurrent()
        assertEquals(0, newcomerPackets)
        processor.processPacket(packetFrom(newcomer, MessageType.ANNOUNCE))
        runCurrent()
        assertEquals(1, newcomerPackets)
        assertEquals(PacketProcessor.MAX_PEER_ACTORS, processor.activePeerActorCount)
    }

    @Test
    fun `shutdown releases every actor`() {
        val processor = PacketProcessor(MY_PEER_ID).also { it.delegate = NoopDelegate() }

        repeat(10) { processor.processPacket(packetFrom(peerID(it))) }
        assertEquals(10, processor.activePeerActorCount)

        processor.shutdown()

        assertEquals(0, processor.activePeerActorCount)
        processor.processPacket(packetFrom(peerID(100)))
        assertEquals(0, processor.activePeerActorCount)
    }

    private fun processor(): PacketProcessor =
        PacketProcessor(MY_PEER_ID).also {
            it.delegate = NoopDelegate()
            processors += it
        }

    private fun peerID(index: Int): String = String.format("%016x", index + 1)

    private fun packetFrom(peerID: String, type: MessageType = MessageType.MESSAGE): RoutedPacket {
        val packet = BitchatPacket(
            version = 1u,
            type = type.value,
            senderID = peerID.hexToBytes(),
            recipientID = SpecialRecipients.BROADCAST,
            timestamp = 1u,
            payload = byteArrayOf(0x01),
            ttl = 7u
        )
        return RoutedPacket(packet, peerID, "direct-link")
    }

    private open class NoopDelegate : PacketProcessorDelegate {
        override fun validatePacketSecurity(packet: BitchatPacket, peerID: String) = true
        override fun updatePeerLastSeen(peerID: String) = Unit
        override fun getPeerNickname(peerID: String): String? = null
        override fun getNetworkSize() = 1
        override fun getBroadcastRecipient(): ByteArray = SpecialRecipients.BROADCAST
        override fun handleNoiseHandshake(routed: RoutedPacket) = true
        override fun handleNoiseEncrypted(routed: RoutedPacket) = true
        override suspend fun handleAnnounce(routed: RoutedPacket) = true
        override fun handleMessage(routed: RoutedPacket) = Unit
        override fun handleLeave(routed: RoutedPacket) = Unit
        override fun handleFragment(packet: BitchatPacket): BitchatPacket? = null
        override fun handleRequestSync(routed: RoutedPacket) = Unit
        override fun sendAnnouncementToPeer(peerID: String) = Unit
        override fun sendCachedMessages(peerID: String) = Unit
        override fun relayPacket(routed: RoutedPacket) = Unit
        override fun sendToPeer(peerID: String, routed: RoutedPacket) = false
    }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val MY_PEER_ID = "1111222233334444"
    }
}
