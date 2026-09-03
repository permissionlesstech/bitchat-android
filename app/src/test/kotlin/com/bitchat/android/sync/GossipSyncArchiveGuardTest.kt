package com.bitchat.android.sync

import com.bitchat.android.model.RequestSyncPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The LEAVE and stale purges remove a sender's announcement and messages together. A
 * message accepted on a persisted key alone, from a sender the live registry no longer
 * holds, must not re-enter the archive behind that purge: nothing would prune it and every
 * requester would be served it again. Present peers and this device's own broadcasts are
 * archived exactly as before.
 */
class GossipSyncArchiveGuardTest {

    private val sent = mutableListOf<Pair<String, BitchatPacket>>()
    private lateinit var scope: CoroutineScope
    private lateinit var manager: GossipSyncManager

    private val requester = "aabbccddeeff0011"
    private val sender = ByteArray(8) { 0x11 }
    private val senderID = sender.joinToString("") { "%02x".format(it) }

    private var livePeers = setOf<String>()

    private val delegate = object : GossipSyncManager.Delegate {
        override fun hasLivePeer(peerID: String): Boolean = peerID in livePeers
        override fun sendPacket(packet: BitchatPacket) = Unit
        override fun sendPacketToPeer(peerID: String, packet: BitchatPacket) {
            sent += peerID to packet
        }
        override fun signPacketForBroadcast(packet: BitchatPacket): BitchatPacket = packet
    }

    private val config = object : GossipSyncManager.ConfigProvider {
        override fun seenCapacity(): Int = 100
        override fun gcsMaxBytes(): Int = 400
        override fun gcsTargetFpr(): Double = 0.01
    }

    @Before
    fun setUp() {
        sent.clear()
        livePeers = emptySet()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        manager = GossipSyncManager(myPeerID = "1122334455667788", scope = scope, configProvider = config)
        manager.delegate = delegate
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun broadcastMessage(ageMillis: Long): BitchatPacket = BitchatPacket(
        version = 1u,
        type = MessageType.MESSAGE.value,
        senderID = sender,
        recipientID = SpecialRecipients.BROADCAST,
        timestamp = (System.currentTimeMillis() - ageMillis).toULong(),
        payload = "from before they left".toByteArray(),
        signature = ByteArray(64) { 0x22 },
        ttl = 0u
    )

    private fun announce(ageMillis: Long): BitchatPacket = BitchatPacket(
        version = 1u,
        type = MessageType.ANNOUNCE.value,
        senderID = sender,
        recipientID = null,
        timestamp = (System.currentTimeMillis() - ageMillis).toULong(),
        payload = "nickname".toByteArray(),
        signature = ByteArray(64) { 0x44 },
        ttl = 7u
    )

    /** A filter the requester builds when it holds nothing: everything we have is missing. */
    private fun requestForNothingHeld() = RequestSyncPacket(p = 7, m = 1, data = ByteArray(0))

    @Test
    fun `a message from a sender the live registry has forgotten is not archived or served`() {
        manager.onPublicPacketSeen(broadcastMessage(ageMillis = 60_000L))

        manager.handleRequestSync(requester, requestForNothingHeld())

        assertTrue("nothing must be served for a sender the registry has forgotten", sent.isEmpty())
    }

    @Test
    fun `a message from a present peer is archived and served even with no announcement stored`() {
        livePeers = setOf(senderID)
        manager.onPublicPacketSeen(broadcastMessage(ageMillis = 60_000L))

        manager.handleRequestSync(requester, requestForNothingHeld())

        assertEquals("a present peer's message is archived as before", 1, sent.size)
        assertEquals(MessageType.MESSAGE.value, sent.single().second.type)
    }

    @Test
    fun `this device's own broadcasts are archived regardless of the registry`() {
        val ownSender = "1122334455667788".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        manager.onPublicPacketSeen(broadcastMessage(ageMillis = 1_000L).copy(senderID = ownSender))

        manager.handleRequestSync(requester, requestForNothingHeld())

        assertEquals("own broadcasts must keep being served", 1, sent.size)
    }
}
