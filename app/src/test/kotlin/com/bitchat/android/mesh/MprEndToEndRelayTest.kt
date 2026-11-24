package com.bitchat.android.mesh

import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import com.bitchat.android.services.meshgraph.BroadcastPruningPolicy
import com.bitchat.android.services.meshgraph.MeshGraphService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.security.MessageDigest

/**
 * End-to-end multi-node simulation exercising the real SecurityManager + PacketProcessor +
 * PacketRelayManager + BroadcastPruningPolicy pipeline.
 *
 * Specifically covers the adversarial 8-node arrival-order graph where a node receives its first
 * copy of a broadcast from a non-electing previous hop before receiving a second copy from an
 * electing previous hop.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MprEndToEndRelayTest {

    private var nowMs: Long = 100_000L

    @Before
    fun setUp() {
        nowMs = 100_000L
        MeshGraphService.resetForTesting { nowMs }
    }

    @After
    fun tearDown() {
        MeshGraphService.resetForTesting()
    }

    @Test
    fun `adversarial arrival order delivers broadcast to all 8 nodes exactly once and rejects forged duplicates`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val nodeIds = (0..7).map { i -> "%016x".format(i) }

        // Exact 8-node counterexample edges where 6->5 arrives before 2->5 and 2->4 arrives before 3->4:
        val rawEdges = listOf(
            0 to 2, 0 to 3,
            1 to 5, 1 to 6, 1 to 7,
            2 to 3, 2 to 4, 2 to 5,
            3 to 4, 3 to 6,
            4 to 5, 4 to 6, 4 to 7,
            5 to 6, 5 to 7
        )
        val adjacency = mutableMapOf<String, MutableSet<String>>()
        nodeIds.forEach { adjacency[it] = linkedSetOf() }
        for ((a, b) in rawEdges) {
            adjacency[nodeIds[a]]!!.add(nodeIds[b])
            adjacency[nodeIds[b]]!!.add(nodeIds[a])
        }

        val graph = MeshGraphService.getInstance()
        adjacency.forEach { (peer, neighbors) ->
            graph.updateFromAnnouncement(peer, peer, neighbors.toList(), 1_000UL)
        }

        val originId = nodeIds[0]
        val originSigningKey = ByteArray(32) { 0x42 }

        // Deterministic synthetic signature verification matching Ed25519 semantics without
        // requiring Android Keystore: valid signature == SHA-256(key || data) doubled to 64 bytes.
        fun sign(data: ByteArray, key: ByteArray): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256").digest(key + data)
            return digest + digest
        }

        val deliveredCounts = mutableMapOf<String, Int>().withDefault { 0 }
        val relayCounts = mutableMapOf<String, Int>().withDefault { 0 }

        data class DeliveryEvent(val from: String, val to: String, val routed: RoutedPacket)
        val pendingEvents = ArrayDeque<DeliveryEvent>()

        class SimulatedNode(val peerID: String) {
            val encryption: EncryptionService = mock<EncryptionService>().apply {
                whenever(verifyEd25519Signature(any(), any(), any())).thenAnswer { inv ->
                    val sig = inv.getArgument<ByteArray>(0)
                    val data = inv.getArgument<ByteArray>(1)
                    val pub = inv.getArgument<ByteArray>(2)
                    sig.contentEquals(sign(data, pub))
                }
            }
            val securityManager = SecurityManager(encryption, peerID).apply {
                delegate = object : SecurityManagerDelegate {
                    override fun onKeyExchangeCompleted(
                        peerID: String,
                        authenticatedRemoteStaticKey: ByteArray,
                        authenticatedSessionToken: ByteArray,
                        directRelayAddress: String?,
                        ingressLinkID: String?
                    ) = Unit
                    override fun sendHandshakeResponse(peerID: String, response: ByteArray) = Unit
                    override fun getPeerInfo(peerID: String): PeerInfo? {
                        return if (peerID == originId) {
                            PeerInfo(
                                id = originId,
                                nickname = "origin",
                                isConnected = true,
                                isDirectConnection = false,
                                noisePublicKey = null,
                                signingPublicKey = originSigningKey,
                                isVerifiedNickname = true,
                                lastSeen = System.currentTimeMillis()
                            )
                        } else null
                    }
                }
            }
            val processor = PacketProcessor(peerID, dispatcher = dispatcher)

            init {
                processor.delegate = object : PacketProcessorDelegate {
                    override fun validatePacketSecurity(packet: BitchatPacket, peerID: String): Boolean =
                        securityManager.validatePacket(packet, peerID)

                    override fun validateDuplicatePacketForRelay(packet: BitchatPacket, peerID: String): Boolean =
                        securityManager.validateAuthenticatedDuplicate(packet, peerID)

                    override fun updatePeerLastSeen(peerID: String) = Unit
                    override fun getPeerNickname(peerID: String): String? = peerID
                    override fun getNetworkSize(): Int = nodeIds.size
                    override fun getBroadcastRecipient(): ByteArray = SpecialRecipients.BROADCAST
                    override fun getDirectConnectedPeers(): Set<String> = adjacency[this@SimulatedNode.peerID].orEmpty()
                    override fun hasUnidentifiedDirectLink(): Boolean = false
                    override fun handleNoiseHandshake(routed: RoutedPacket): Boolean = false
                    override fun handleNoiseEncrypted(routed: RoutedPacket): Boolean = false
                    override suspend fun handleAnnounce(routed: RoutedPacket): AnnounceHandlingResult =
                        AnnounceHandlingResult.Rejected
                    override suspend fun handleMessage(routed: RoutedPacket) {
                        deliveredCounts[this@SimulatedNode.peerID] =
                            deliveredCounts.getValue(this@SimulatedNode.peerID) + 1
                    }
                    override suspend fun handleLeave(routed: RoutedPacket) = Unit
                    override fun handleFragment(packet: BitchatPacket): BitchatPacket? = null
                    override fun handleRequestSync(routed: RoutedPacket) = Unit
                    override fun sendAnnouncementToPeer(peerID: String) = Unit
                    override fun sendToPeer(peerID: String, routed: RoutedPacket): Boolean = false
                    override fun relayPacket(routed: RoutedPacket) {
                        relayCounts[this@SimulatedNode.peerID] =
                            relayCounts.getValue(this@SimulatedNode.peerID) + 1
                        for (neighbor in adjacency[this@SimulatedNode.peerID].orEmpty()) {
                            val skip = BroadcastPruningPolicy.shouldSkipTarget(
                                routed = routed,
                                myPeerID = this@SimulatedNode.peerID,
                                targetPeerID = neighbor,
                                targetAddress = "addr-$neighbor",
                                resolvedPreviousHopPeerID = routed.previousHopPeerID,
                                graphService = graph
                            )
                            if (!skip) {
                                pendingEvents.addLast(
                                    DeliveryEvent(
                                        from = this@SimulatedNode.peerID,
                                        to = neighbor,
                                        routed = RoutedPacket(
                                            packet = routed.packet,
                                            peerID = originId,
                                            relayAddress = "addr-${this@SimulatedNode.peerID}",
                                            previousHopPeerID = this@SimulatedNode.peerID
                                        )
                                    )
                                )
                            }
                        }
                    }
                }
            }

            fun shutdown() {
                processor.shutdown()
                securityManager.shutdown()
            }
        }

        val nodes = nodeIds.associateWith { SimulatedNode(it) }
        try {
            val unsigned = BitchatPacket(
                version = 1u,
                type = MessageType.MESSAGE.value,
                senderID = hexToBytes(originId),
                recipientID = SpecialRecipients.BROADCAST,
                timestamp = System.currentTimeMillis().toULong(),
                payload = "mpr-arrival-order-test".toByteArray(),
                ttl = 7u
            )
            val signed = unsigned.copy(signature = sign(unsigned.toBinaryDataForSigning()!!, originSigningKey))

            // Inject initial transmission from node 0 to its neighbors (2 and 3)
            for (neighbor in adjacency[originId].orEmpty()) {
                pendingEvents.addLast(
                    DeliveryEvent(
                        from = originId,
                        to = neighbor,
                        routed = RoutedPacket(
                            packet = signed,
                            peerID = originId,
                            relayAddress = "addr-$originId",
                            previousHopPeerID = originId
                        )
                    )
                )
            }

            // Drive event queue with adversarial priority:
            // - delay 2->5 until after 6->5 has been processed (if present)
            // - delay 3->4 until after 2->4 has been processed (if present)
            fun pickNextEvent(): DeliveryEvent {
                val list = pendingEvents.toList()
                val preferredIndex = list.indexOfFirst { ev ->
                    val isDelayedEdge =
                        (ev.from == nodeIds[2] && ev.to == nodeIds[5]) ||
                        (ev.from == nodeIds[3] && ev.to == nodeIds[4])
                    !isDelayedEdge
                }
                val idx = if (preferredIndex >= 0) preferredIndex else 0
                val chosen = list[idx]
                pendingEvents.remove(chosen)
                return chosen
            }

            while (pendingEvents.isNotEmpty()) {
                val ev = pickNextEvent()
                nodes[ev.to]!!.processor.processPacket(ev.routed)
                runCurrent()
            }

            // Every non-origin node (specifically including node 7!) must receive the broadcast
            // exactly once at the application layer, and relay at most once.
            for (i in 1..7) {
                val pid = nodeIds[i]
                assertEquals("Node $i must deliver broadcast exactly once", 1, deliveredCounts.getValue(pid))
                assertTrue("Node $i must relay at most once", relayCounts.getValue(pid) <= 1)
            }

            // Security regression check: a forged duplicate (invalid signature) arriving at a node
            // whose first copy was MPR-suppressed must NEVER trigger a relay.
            val unsigned2 = unsigned.copy(timestamp = (System.currentTimeMillis() + 100L).toULong())
            val signed2 = unsigned2.copy(signature = sign(unsigned2.toBinaryDataForSigning()!!, originSigningKey))
            val forged2 = signed2.copy(signature = ByteArray(64) { 0xFF.toByte() })

            val node4 = nodes[nodeIds[4]]!!
            val relaysBefore = relayCounts.getValue(nodeIds[4])
            // First valid copy from node 2 (non-electing for 4) -> suppressed
            node4.processor.processPacket(
                RoutedPacket(signed2, originId, "addr-${nodeIds[2]}", previousHopPeerID = nodeIds[2])
            )
            runCurrent()
            assertEquals(relaysBefore, relayCounts.getValue(nodeIds[4]))

            // Forged copy from node 3 (electing for 4) -> MUST be rejected
            node4.processor.processPacket(
                RoutedPacket(forged2, originId, "addr-${nodeIds[3]}", previousHopPeerID = nodeIds[3])
            )
            runCurrent()
            assertEquals("Forged duplicate must never trigger relay", relaysBefore, relayCounts.getValue(nodeIds[4]))

            // Genuine signed copy from node 3 -> relays once
            node4.processor.processPacket(
                RoutedPacket(signed2, originId, "addr-${nodeIds[3]}", previousHopPeerID = nodeIds[3])
            )
            runCurrent()
            assertEquals("Authenticated duplicate from electing hop must relay once", relaysBefore + 1, relayCounts.getValue(nodeIds[4]))
        } finally {
            nodes.values.forEach(SimulatedNode::shutdown)
        }
    }

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
