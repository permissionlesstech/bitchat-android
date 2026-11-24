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

        var wallMs = 1_000_000L
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
            val securityManager = SecurityManager(encryption, peerID, clockMs = { wallMs }).apply {
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
                                signingPublicKey = originSigningKey.copyOf(),
                                isVerifiedNickname = true,
                                lastSeen = wallMs
                            )
                        } else null
                    }
                }
            }
            val processor = PacketProcessor(peerID, dispatcher = dispatcher, relayClockMs = { wallMs })

            init {
                processor.delegate = object : PacketProcessorDelegate {
                    override fun validatePacketSecurity(packet: BitchatPacket, peerID: String): Boolean =
                        securityManager.validatePacket(packet, peerID)

                    override fun validateDuplicatePacketForRelay(packet: BitchatPacket, peerID: String): Boolean =
                        securityManager.validateAuthenticatedDuplicate(packet, peerID)

                    override fun updatePeerLastSeen(peerID: String) = Unit
                    override fun getPeerNickname(peerID: String): String? = peerID
                    override fun getNetworkSize(): Int = nodeIds.size
                    override fun getBroadcastRecipient(): ByteArray = SpecialRecipients.BROADCAST.copyOf()
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
                timestamp = wallMs.toULong(),
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
            wallMs += 100L
            val unsigned2 = unsigned.copy(timestamp = wallMs.toULong())
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

    @Test
    fun `unsigned duplicate fragment is rejected fail-closed and injectable clock enforces exact expiry`() = runTest {
        var wallMs = 1_000_000L
        val myPeer = "0000000000000009"
        val originId = "0000000000000001"
        val signingKey = ByteArray(32) { 0x55 }

        fun sign(data: ByteArray, key: ByteArray): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256").digest(key + data)
            return digest + digest
        }

        val encryption: EncryptionService = mock<EncryptionService>().apply {
            whenever(verifyEd25519Signature(any(), any(), any())).thenAnswer { inv ->
                inv.getArgument<ByteArray>(0).contentEquals(
                    sign(inv.getArgument(1), inv.getArgument(2))
                )
            }
        }
        val security = SecurityManager(encryption, myPeer, clockMs = { wallMs }).apply {
            delegate = object : SecurityManagerDelegate {
                override fun onKeyExchangeCompleted(
                    peerID: String,
                    authenticatedRemoteStaticKey: ByteArray,
                    authenticatedSessionToken: ByteArray,
                    directRelayAddress: String?,
                    ingressLinkID: String?
                ) = Unit
                override fun sendHandshakeResponse(peerID: String, response: ByteArray) = Unit
                override fun getPeerInfo(peerID: String): PeerInfo? = PeerInfo(
                    id = originId,
                    nickname = "origin",
                    isConnected = true,
                    isDirectConnection = false,
                    noisePublicKey = null,
                    signingPublicKey = signingKey.copyOf(),
                    isVerifiedNickname = true,
                    lastSeen = wallMs
                )
            }
        }

        try {
            // 1. Legacy unsigned FRAGMENT is accepted on first arrival but rejected on duplicate-relay validation
            val unsignedFragment = BitchatPacket(
                version = 1u,
                type = MessageType.FRAGMENT.value,
                senderID = hexToBytes(originId),
                recipientID = SpecialRecipients.BROADCAST,
                timestamp = wallMs.toULong(),
                payload = byteArrayOf(1, 2, 3, 4),
                signature = null,
                ttl = 5u
            )
            assertTrue("First unsigned FRAGMENT must remain compatible", security.validatePacket(unsignedFragment, originId))
            org.junit.Assert.assertFalse(
                "Unsigned duplicate FRAGMENT must fail closed in validateAuthenticatedDuplicate",
                security.validateAuthenticatedDuplicate(unsignedFragment, originId)
            )

            // 1b. Non-null forged signature on FRAGMENT must be rejected fail-closed on first ingress,
            // while a validly signed FRAGMENT is accepted on both first ingress and duplicate validation.
            val baseSignedFragment = unsignedFragment.copy(timestamp = (wallMs + 1L).toULong())
            val forgedFragment = baseSignedFragment.copy(signature = ByteArray(64) { 0xFF.toByte() })
            org.junit.Assert.assertFalse(
                "Forged non-null signature on FRAGMENT must fail closed on first ingress",
                security.validatePacket(forgedFragment, originId)
            )
            val validSignedFragment = baseSignedFragment.copy(
                timestamp = (wallMs + 2L).toULong(),
                signature = sign(
                    baseSignedFragment.copy(timestamp = (wallMs + 2L).toULong()).toBinaryDataForSigning()!!,
                    signingKey
                )
            )
            assertTrue("Validly signed FRAGMENT must pass first ingress", security.validatePacket(validSignedFragment, originId))
            assertTrue(
                "Validly signed FRAGMENT must pass duplicate relay validation",
                security.validateAuthenticatedDuplicate(validSignedFragment, originId)
            )

            // 2. Signed MESSAGE duplicate passes up to MESSAGE_TIMEOUT and fails deterministically at +1 ms
            val unsignedMsg = BitchatPacket(
                version = 1u,
                type = MessageType.MESSAGE.value,
                senderID = hexToBytes(originId),
                recipientID = SpecialRecipients.BROADCAST,
                timestamp = wallMs.toULong(),
                payload = "boundary-test".toByteArray(),
                ttl = 5u
            )
            val signedMsg = unsignedMsg.copy(signature = sign(unsignedMsg.toBinaryDataForSigning()!!, signingKey))
            assertTrue(security.validatePacket(signedMsg, originId))

            wallMs += com.bitchat.android.util.AppConstants.Security.MESSAGE_TIMEOUT_MS
            assertTrue(
                "Signed duplicate at exact timeout boundary must still validate",
                security.validateAuthenticatedDuplicate(signedMsg, originId)
            )

            wallMs += 1L
            org.junit.Assert.assertFalse(
                "Signed duplicate past timeout boundary must be rejected",
                security.validateAuthenticatedDuplicate(signedMsg, originId)
            )
        } finally {
            security.shutdown()
        }
    }

    @Test
    fun `first unchanged announce after graph expiry forces conservative flood through production MessageHandler`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var relayClock = 1_000_000L

        val prevHopNoiseKey = ByteArray(32) { (0x10 + it).toByte() }
        val prevHopSigningKey = ByteArray(32) { (0x50 + it).toByte() }
        val prevHop = com.bitchat.android.noise.NoisePeerIdentity.derivePeerID(prevHopNoiseKey)!!
        val peerB = "0000000000000002"
        val me = "0000000000000009"

        fun sign(data: ByteArray, key: ByteArray): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256").digest(key + data)
            return digest + digest
        }

        val graph = MeshGraphService.getInstance()
        val initialTs = relayClock.toULong()
        graph.updateFromAnnouncement(prevHop, "P", listOf(peerB, me), initialTs)
        graph.updateFromAnnouncement(peerB, "B", listOf(prevHop, me), initialTs)
        graph.updateFromAnnouncement(me, "M", listOf(prevHop, peerB), initialTs)

        // Expire prevHop's graph announcement while keeping me and peerB fresh
        nowMs += MeshGraphService.DEFAULT_EDGE_MAX_AGE_MS + 1_000L
        graph.updateFromAnnouncement(peerB, "B", listOf(prevHop, me), initialTs + 1UL)
        graph.updateFromAnnouncement(me, "M", listOf(prevHop, peerB), initialTs + 1UL)

        val messageHandler = MessageHandler(me, mock(), clockMs = { relayClock })
        messageHandler.delegate = object : MessageHandlerDelegate {
            override fun addOrUpdatePeer(peerID: String, nickname: String) = false
            override fun removePeer(peerID: String) = Unit
            override fun updatePeerNickname(peerID: String, nickname: String) = Unit
            override fun getPeerNickname(peerID: String): String? = "P"
            override fun getNetworkSize() = 10
            override fun getMyNickname() = "M"
            override fun getPeerInfo(peerID: String): PeerInfo? = PeerInfo(
                id = prevHop,
                nickname = "P",
                isConnected = true,
                isDirectConnection = true,
                noisePublicKey = prevHopNoiseKey.copyOf(),
                signingPublicKey = prevHopSigningKey.copyOf(),
                isVerifiedNickname = true,
                lastSeen = relayClock
            )
            override fun updatePeerInfoFromVerifiedAnnouncement(
                peerID: String,
                nickname: String,
                noisePublicKey: ByteArray,
                signingPublicKey: ByteArray,
                isVerified: Boolean,
                capabilities: com.bitchat.android.model.PeerCapabilities?
            ) = false // Existing peer (isFirst = false)
            override fun sendPacket(packet: BitchatPacket) = Unit
            override fun relayPacket(routed: RoutedPacket) = Unit
            override fun getBroadcastRecipient(): ByteArray = SpecialRecipients.BROADCAST.copyOf()
            override fun verifySignature(packet: BitchatPacket, peerID: String) = true
            override fun encryptForPeer(data: ByteArray, recipientPeerID: String): ByteArray? = null
            override fun decryptFromPeer(encryptedData: ByteArray, senderPeerID: String): com.bitchat.android.noise.NoiseDecryptionResult? = null
            override fun verifyEd25519Signature(signature: ByteArray, data: ByteArray, publicKey: ByteArray): Boolean =
                signature.contentEquals(sign(data, publicKey))
            override fun hasNoiseSession(peerID: String) = false
            override fun removeNoiseSession(peerID: String) = Unit
            override fun initiateNoiseHandshake(peerID: String) = Unit
            override fun processNoiseHandshakeMessage(payload: ByteArray, peerID: String): ByteArray? = null
            override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? = null
            override fun onMessageReceived(message: com.bitchat.android.model.BitchatMessage) = Unit
            override fun onChannelLeave(channel: String, fromPeer: String) = Unit
            override fun onDeliveryAckReceived(messageID: String, peerID: String) = Unit
            override fun onReadReceiptReceived(messageID: String, peerID: String) = Unit
            override fun onVerifyChallengeReceived(peerID: String, payload: ByteArray, timestampMs: Long) = Unit
            override fun onVerifyResponseReceived(peerID: String, payload: ByteArray, timestampMs: Long) = Unit
        }

        val encryption: EncryptionService = mock<EncryptionService>().apply {
            whenever(verifyEd25519Signature(any(), any(), any())).thenAnswer { inv ->
                inv.getArgument<ByteArray>(0).contentEquals(
                    sign(inv.getArgument(1), inv.getArgument(2))
                )
            }
        }
        val securityManager = SecurityManager(encryption, me, clockMs = { relayClock }).apply {
            delegate = object : SecurityManagerDelegate {
                override fun onKeyExchangeCompleted(
                    peerID: String,
                    authenticatedRemoteStaticKey: ByteArray,
                    authenticatedSessionToken: ByteArray,
                    directRelayAddress: String?,
                    ingressLinkID: String?
                ) = Unit
                override fun sendHandshakeResponse(peerID: String, response: ByteArray) = Unit
                override fun getPeerInfo(peerID: String): PeerInfo? = PeerInfo(
                    id = prevHop,
                    nickname = "P",
                    isConnected = true,
                    isDirectConnection = true,
                    noisePublicKey = prevHopNoiseKey.copyOf(),
                    signingPublicKey = prevHopSigningKey.copyOf(),
                    isVerifiedNickname = true,
                    lastSeen = relayClock
                )
            }
        }

        val relayed = mutableListOf<RoutedPacket>()
        val processor = PacketProcessor(me, dispatcher = dispatcher, relayClockMs = { relayClock })
        processor.delegate = object : PacketProcessorDelegate {
            override fun validatePacketSecurity(packet: BitchatPacket, peerID: String) =
                securityManager.validatePacket(packet, peerID)
            override fun updatePeerLastSeen(peerID: String) = Unit
            override fun getPeerNickname(peerID: String): String? = peerID
            override fun getNetworkSize() = 10
            override fun getBroadcastRecipient(): ByteArray = SpecialRecipients.BROADCAST.copyOf()
            override fun getDirectConnectedPeers(): Set<String> = setOf(prevHop, peerB)
            override fun handleNoiseHandshake(routed: RoutedPacket) = false
            override fun handleNoiseEncrypted(routed: RoutedPacket) = false
            override suspend fun handleAnnounce(routed: RoutedPacket): AnnounceHandlingResult =
                messageHandler.handleAnnounceWithResult(routed)
            override suspend fun handleMessage(routed: RoutedPacket) = Unit
            override suspend fun handleLeave(routed: RoutedPacket) = Unit
            override fun handleFragment(packet: BitchatPacket): BitchatPacket? = null
            override fun handleRequestSync(routed: RoutedPacket) = Unit
            override fun sendAnnouncementToPeer(peerID: String) = Unit
            override fun relayPacket(routed: RoutedPacket) { relayed += routed }
            override fun sendToPeer(peerID: String, routed: RoutedPacket) = false
        }

        try {
            // Unchanged neighbor list [peerB, me], newer packet timestamp, valid Ed25519 signature
            val baseTlv = com.bitchat.android.model.IdentityAnnouncement
                .forLocalPeer("P", prevHopNoiseKey, prevHopSigningKey)
                .encode()!!
            val gossipTlv = com.bitchat.android.services.meshgraph.GossipTLV.encodeNeighbors(listOf(peerB, me))
            val unsignedAnnounce = BitchatPacket(
                version = 1u,
                type = MessageType.ANNOUNCE.value,
                senderID = hexToBytes(prevHop),
                recipientID = SpecialRecipients.BROADCAST,
                timestamp = initialTs + 2UL,
                payload = baseTlv + gossipTlv,
                ttl = 5u
            )
            val signedAnnounce = unsignedAnnounce.copy(
                signature = sign(unsignedAnnounce.toBinaryDataForSigning()!!, prevHopSigningKey)
            )

            processor.processPacket(RoutedPacket(signedAnnounce, prevHop, "link-p", previousHopPeerID = prevHop))
            runCurrent()

            assertEquals("First unchanged ANNOUNCE after expiry must flood conservatively", 1, relayed.size)
            assertTrue("Stale recovery ANNOUNCE must disable neighbor pruning", relayed.single().skipNeighborPruning)
        } finally {
            processor.shutdown()
            securityManager.shutdown()
            messageHandler.shutdown()
        }
    }

    @Test
    fun `11-node adversarial arrival order delivers unsigned multi-fragment broadcast to all nodes including node 7`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var wallMs = 2_000_000L
        val nodeIds = (0..10).map { i -> "%016x".format(i) }

        val rawEdges = listOf(
            0 to 2, 0 to 3, 0 to 8,
            1 to 5, 1 to 6, 1 to 7, 1 to 10,
            2 to 3, 2 to 4, 2 to 5, 2 to 8, 2 to 9, 2 to 10,
            3 to 4, 3 to 6, 3 to 9,
            4 to 5, 4 to 6, 4 to 7, 4 to 8, 4 to 10,
            5 to 6, 5 to 7, 5 to 9,
            6 to 8, 6 to 9, 6 to 10,
            7 to 9, 7 to 10,
            8 to 9, 9 to 10
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
        val originSigningKey = ByteArray(32) { 0x63 }
        fun sign(data: ByteArray, key: ByteArray): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256").digest(key + data)
            return digest + digest
        }

        // Build a real multi-fragment broadcast packet using production FragmentManager (signature = null on fragments)
        val largePayload = ByteArray(1_200) { (it and 0xFF).toByte() }
        val innerUnsigned = BitchatPacket(
            version = 2u,
            type = MessageType.FILE_TRANSFER.value,
            senderID = hexToBytes(originId),
            recipientID = SpecialRecipients.BROADCAST,
            timestamp = wallMs.toULong(),
            payload = largePayload,
            ttl = 7u
        )
        val innerSigned = innerUnsigned.copy(
            signature = sign(innerUnsigned.toBinaryDataForSigning()!!, originSigningKey)
        )
        val senderFragMgr = FragmentManager()
        val unsignedFragments = senderFragMgr.createFragments(innerSigned)
        assertTrue("Expected multi-fragment transfer", unsignedFragments.size >= 2)
        assertTrue("Current senders emit unsigned fragment envelopes", unsignedFragments.all { it.signature == null })
        senderFragMgr.shutdown()

        val reassembledByNode = mutableMapOf<String, MutableList<BitchatPacket>>()
        data class FragEvent(val from: String, val to: String, val routed: RoutedPacket)
        val pending = mutableListOf<FragEvent>()

        class FragNode(val peerID: String) {
            val fragManager = FragmentManager()
            val encryption: EncryptionService = mock<EncryptionService>().apply {
                whenever(verifyEd25519Signature(any(), any(), any())).thenAnswer { inv ->
                    inv.getArgument<ByteArray>(0).contentEquals(sign(inv.getArgument(1), inv.getArgument(2)))
                }
            }
            val securityManager = SecurityManager(encryption, peerID, clockMs = { wallMs }).apply {
                delegate = object : SecurityManagerDelegate {
                    override fun onKeyExchangeCompleted(
                        peerID: String,
                        authenticatedRemoteStaticKey: ByteArray,
                        authenticatedSessionToken: ByteArray,
                        directRelayAddress: String?,
                        ingressLinkID: String?
                    ) = Unit
                    override fun sendHandshakeResponse(peerID: String, response: ByteArray) = Unit
                    override fun getPeerInfo(peerID: String): PeerInfo? =
                        if (peerID == originId) PeerInfo(
                            id = originId, nickname = "origin", isConnected = true, isDirectConnection = false,
                            noisePublicKey = null, signingPublicKey = originSigningKey.copyOf(),
                            isVerifiedNickname = true, lastSeen = wallMs
                        ) else null
                }
            }
            val processor = PacketProcessor(peerID, dispatcher = dispatcher, relayClockMs = { wallMs })

            init {
                processor.delegate = object : PacketProcessorDelegate {
                    override fun validatePacketSecurity(packet: BitchatPacket, peerID: String) =
                        securityManager.validatePacket(packet, peerID)
                    override fun validateDuplicatePacketForRelay(packet: BitchatPacket, peerID: String) =
                        securityManager.validateAuthenticatedDuplicate(packet, peerID)
                    override fun updatePeerLastSeen(peerID: String) = Unit
                    override fun getPeerNickname(peerID: String): String? = peerID
                    override fun getNetworkSize() = nodeIds.size
                    override fun getBroadcastRecipient(): ByteArray = SpecialRecipients.BROADCAST.copyOf()
                    override fun getDirectConnectedPeers(): Set<String> = adjacency[this@FragNode.peerID].orEmpty()
                    override fun handleNoiseHandshake(routed: RoutedPacket) = false
                    override fun handleNoiseEncrypted(routed: RoutedPacket) = false
                    override suspend fun handleAnnounce(routed: RoutedPacket) = AnnounceHandlingResult.Rejected
                    override suspend fun handleMessage(routed: RoutedPacket) {
                        reassembledByNode.getOrPut(this@FragNode.peerID) { mutableListOf() }.add(routed.packet)
                    }
                    override suspend fun handleLeave(routed: RoutedPacket) = Unit
                    override fun handleFragment(packet: BitchatPacket): BitchatPacket? =
                        fragManager.handleFragment(packet)
                    override fun handleRequestSync(routed: RoutedPacket) = Unit
                    override fun sendAnnouncementToPeer(peerID: String) = Unit
                    override fun sendToPeer(peerID: String, routed: RoutedPacket) = false
                    override fun relayPacket(routed: RoutedPacket) {
                        for (neighbor in adjacency[this@FragNode.peerID].orEmpty()) {
                            val skip = BroadcastPruningPolicy.shouldSkipTarget(
                                routed = routed,
                                myPeerID = this@FragNode.peerID,
                                targetPeerID = neighbor,
                                targetAddress = "addr-$neighbor",
                                resolvedPreviousHopPeerID = routed.previousHopPeerID,
                                graphService = graph
                            )
                            if (!skip) {
                                pending.add(
                                    FragEvent(
                                        from = this@FragNode.peerID,
                                        to = neighbor,
                                        routed = RoutedPacket(
                                            packet = routed.packet,
                                            peerID = originId,
                                            relayAddress = "addr-${this@FragNode.peerID}",
                                            previousHopPeerID = this@FragNode.peerID
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
                fragManager.shutdown()
            }
        }

        val nodes = nodeIds.associateWith { FragNode(it) }
        try {
            // Seed all fragments from node 0 to its neighbors (2, 3, 8)
            for (frag in unsignedFragments) {
                for (neighbor in adjacency[originId].orEmpty()) {
                    pending.add(
                        FragEvent(
                            from = originId,
                            to = neighbor,
                            routed = RoutedPacket(
                                packet = frag,
                                peerID = originId,
                                relayAddress = "addr-$originId",
                                previousHopPeerID = originId
                            )
                        )
                    )
                }
            }

            // Deliver the exact adversarial prefix order first for each fragment, then drain
            val priorityEdges = listOf(
                0 to 8, 0 to 2, 8 to 6, 6 to 5, 6 to 10, 2 to 4, 2 to 9
            ).map { (a, b) -> nodeIds[a] to nodeIds[b] }

            while (pending.isNotEmpty()) {
                var chosenIndex = -1
                for ((pf, pt) in priorityEdges) {
                    val idx = pending.indexOfFirst { it.from == pf && it.to == pt }
                    if (idx >= 0) {
                        chosenIndex = idx
                        break
                    }
                }
                val ev = pending.removeAt(if (chosenIndex >= 0) chosenIndex else 0)
                nodes[ev.to]!!.processor.processPacket(ev.routed)
                runCurrent()
            }

            // Every non-origin node (specifically including node 7) must reassemble the full payload
            // once, while strict duplicate-fragment authentication remains enforced.
            for (i in 1..10) {
                val pid = nodeIds[i]
                val received = reassembledByNode[pid].orEmpty()
                assertEquals("Node $i must reassemble the broadcast file exactly once", 1, received.size)
                assertTrue(
                    "Node $i payload must match original bytes",
                    received.single().payload.contentEquals(largePayload)
                )
                org.junit.Assert.assertFalse(
                    "Strict duplicate auth must still reject unsigned fragment duplicates on node $i",
                    nodes[pid]!!.securityManager.validateAuthenticatedDuplicate(unsignedFragments.first(), originId)
                )
            }
        } finally {
            nodes.values.forEach(FragNode::shutdown)
        }
    }

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
