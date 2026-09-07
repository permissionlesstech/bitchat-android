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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The archive replay path must mark what it serves as a solicited sync response.
 * BinaryProtocolTest covers the wire format; this pins the replay sites, where the flag could
 * be dropped while the format still supports it.
 */
class GossipSyncRsrFlagTest {

    private val sent = mutableListOf<Pair<String, BitchatPacket>>()
    private lateinit var scope: CoroutineScope
    private lateinit var manager: GossipSyncManager

    private val requester = "aabbccddeeff0011"

    private val delegate = object : GossipSyncManager.Delegate {
        override fun sendPacket(packet: BitchatPacket) = Unit
        override fun sendPacketToPeer(peerID: String, packet: BitchatPacket) {
            sent.add(peerID to packet)
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
        scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        manager = GossipSyncManager(myPeerID = "1122334455667788", scope = scope, configProvider = config)
        manager.delegate = delegate
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun broadcastMessage(payload: String, ageMillis: Long): BitchatPacket = BitchatPacket(
        version = 1u,
        type = MessageType.MESSAGE.value,
        senderID = ByteArray(8) { 0x11 },
        recipientID = SpecialRecipients.BROADCAST,
        timestamp = (System.currentTimeMillis() - ageMillis).toULong(),
        payload = payload.toByteArray(),
        signature = ByteArray(64) { 0x22 },
        ttl = 7u
    )

    /** A filter the requester builds when it holds nothing: everything we have is missing. */
    private fun requestForNothingHeld() = RequestSyncPacket(p = 7, m = 1, data = ByteArray(0))

    @Test
    fun replayedMessagesAreMarkedAsSolicitedSyncResponses() {
        val original = broadcastMessage("history from an hour ago", ageMillis = 60 * 60 * 1000L)
        manager.onPublicPacketSeen(original)

        manager.handleRequestSync(requester, requestForNothingHeld())

        assertEquals("one archived message should have been served", 1, sent.size)
        val (peer, served) = sent.single()
        assertEquals(requester, peer)
        assertTrue("a replayed packet must be marked as a sync response", served.isRSR)
        assertEquals("a sync response must not be relayed onward", 0u.toUByte(), served.ttl)
    }

    @Test
    fun replayPreservesTheOriginalTimestampAndPayload() {
        val original = broadcastMessage("unchanged", ageMillis = 3 * 60 * 60 * 1000L)
        manager.onPublicPacketSeen(original)

        manager.handleRequestSync(requester, requestForNothingHeld())

        val served = sent.single().second
        assertEquals("timestamp must not be rewritten", original.timestamp, served.timestamp)
        assertTrue("payload must not be rewritten", served.payload.contentEquals(original.payload))
        assertTrue(
            "signature must be carried through unchanged",
            served.signature.contentEquals(original.signature)
        )
    }

    private fun announce(ageMillis: Long): BitchatPacket = BitchatPacket(
        version = 1u,
        type = MessageType.ANNOUNCE.value,
        senderID = ByteArray(8) { 0x33 },
        recipientID = null,
        timestamp = (System.currentTimeMillis() - ageMillis).toULong(),
        payload = "nickname".toByteArray(),
        signature = ByteArray(64) { 0x44 },
        ttl = 7u
    )

    /**
     * The announce replay site must mark what it serves too: a dropped announce takes the
     * author's message history with it. The message-path tests leave the announce archive empty
     * and would stay green with this site unmarked.
     */
    @Test
    fun replayedAnnouncesAreMarkedAsSolicitedSyncResponses() {
        manager.onPublicPacketSeen(announce(ageMillis = 150_000L))

        manager.handleRequestSync(requester, requestForNothingHeld())

        assertEquals("the archived announce should have been served", 1, sent.size)
        val served = sent.single().second
        assertEquals(MessageType.ANNOUNCE.value, served.type)
        assertTrue("a replayed announce must be marked as a sync response", served.isRSR)
        assertEquals("a sync response must not be relayed onward", 0u.toUByte(), served.ttl)
    }

    @Test
    fun archivingDoesNotMarkThePacketWeStored() {
        // Serving marks the packet and storing must not. If archiving marked it, a packet would
        // be flagged on paths that are not sync responses.
        val original = broadcastMessage("stored", ageMillis = 1000L)
        manager.onPublicPacketSeen(original)

        assertFalse("the caller's packet must not be mutated", original.isRSR)
    }
}
