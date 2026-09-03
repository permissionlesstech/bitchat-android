package com.bitchat.android.service

import com.bitchat.android.model.RequestSyncPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import com.bitchat.android.sync.GossipSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The shared gossip manager archives a broadcast only when its sender is in a live peer
 * registry, and every transport keeps its own registry. A sender known only to the Wi-Fi
 * Aware registry must count as live, or its broadcasts would never be archived for sync.
 */
class MeshServiceHolderLivenessTest {

    private lateinit var scope: CoroutineScope
    private lateinit var manager: GossipSyncManager

    private val blePeer = "aaaaaaaaaaaaaaaa"
    private val wifiSender = ByteArray(8) { 0x22 }
    private val wifiPeer = wifiSender.joinToString("") { "%02x".format(it) }
    private val stranger = "cccccccccccccccc"
    private val requester = "aabbccddeeff0011"

    private val config = object : GossipSyncManager.ConfigProvider {
        override fun seenCapacity(): Int = 100
        override fun gcsMaxBytes(): Int = 400
        override fun gcsTargetFpr(): Double = 0.01
    }

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        manager = GossipSyncManager(myPeerID = "1122334455667788", scope = scope, configProvider = config)
        MeshServiceHolder.unregisterLivenessProbe("WIFI")
        MeshServiceHolder.registerLivenessProbe("BLE") { it == blePeer }
        MeshServiceHolder.setGossipManager(manager) { it }
    }

    @After
    fun tearDown() {
        MeshServiceHolder.unregisterLivenessProbe("WIFI")
        MeshServiceHolder.unregisterLivenessProbe("BLE")
        scope.cancel()
    }

    private fun broadcastFromWifiSender(): BitchatPacket = BitchatPacket(
        version = 1u,
        type = MessageType.MESSAGE.value,
        senderID = wifiSender,
        recipientID = SpecialRecipients.BROADCAST,
        timestamp = (System.currentTimeMillis() - 1000L).toULong(),
        payload = "over wifi".toByteArray(),
        signature = ByteArray(64) { 0x33 },
        ttl = 7u
    )

    /** A filter the requester builds when it holds nothing: everything we have is missing. */
    private fun requestForNothingHeld() = RequestSyncPacket(p = 7, m = 1, data = ByteArray(0))

    @Test
    fun `a peer known only to the Wi-Fi Aware registry is live once its probe is registered`() {
        val delegate = manager.delegate!!
        assertFalse("before the Wi-Fi probe exists only Bluetooth answers", delegate.hasLivePeer(wifiPeer))

        MeshServiceHolder.registerLivenessProbe("WIFI") { it == wifiPeer }

        assertTrue(delegate.hasLivePeer(wifiPeer))
        assertTrue("the Bluetooth registry still counts", delegate.hasLivePeer(blePeer))
        assertFalse("a peer in no registry is absent", delegate.hasLivePeer(stranger))
    }

    @Test
    fun `removing a transport's probe makes its peers absent again`() {
        val delegate = manager.delegate!!
        MeshServiceHolder.registerLivenessProbe("WIFI") { it == wifiPeer }
        assertTrue(delegate.hasLivePeer(wifiPeer))

        MeshServiceHolder.unregisterLivenessProbe("WIFI")

        assertFalse(delegate.hasLivePeer(wifiPeer))
        assertTrue(delegate.hasLivePeer(blePeer))
    }

    @Test
    fun `a broadcast from a Wi-Fi-only sender is archived and served`() {
        MeshServiceHolder.registerLivenessProbe("WIFI") { it == wifiPeer }
        val original = broadcastFromWifiSender()

        manager.onPublicPacketSeen(original)

        val sent = mutableListOf<BitchatPacket>()
        manager.delegate = object : GossipSyncManager.Delegate {
            override fun sendPacket(packet: BitchatPacket) = Unit
            override fun sendPacketToPeer(peerID: String, packet: BitchatPacket) {
                sent += packet
            }
            override fun signPacketForBroadcast(packet: BitchatPacket): BitchatPacket = packet
        }
        manager.handleRequestSync(requester, requestForNothingHeld())

        assertEquals("the Wi-Fi-only sender's message must be served", 1, sent.size)
        assertTrue(sent[0].payload.contentEquals(original.payload))
    }
}
