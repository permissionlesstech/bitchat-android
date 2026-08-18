package com.bitchat.android.mesh

import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Store-and-forward exists so mail for an offline peer is handed over when it
 * comes back. Two things stopped that working.
 */
class StoreForwardManagerTest {
    private val manager = StoreForwardManager()
    private val delegate = RecordingDelegate()

    init {
        manager.delegate = delegate
    }

    @After
    fun tearDown() = manager.shutdown()

    @Test
    fun `mail cached after a peer was served is delivered on the next reconnect`() = runBlocking {
        // First contact: nothing held, peer is marked as served.
        manager.sendCachedMessages(PEER)
        waitForDelivery()
        assertEquals(0, delegate.sent.size)

        // Peer goes away; a message is queued for it.
        manager.cacheMessage(privateMessage("m-1"), "m-1")

        // It comes back. Before this fix the send was refused outright,
        // because nothing ever removed the peer from the already-sent latch,
        // and the message sat in the cache until it aged out.
        manager.sendCachedMessages(PEER)
        waitForDelivery()

        assertEquals(1, delegate.sent.size)
    }

    @Test
    fun `a peer with nothing new is still not re-sent the same batch`() {
        // The latch has to keep working, or every reconnect replays whatever
        // is still cached.
        manager.cacheMessage(privateMessage("m-1"), "m-1")
        manager.sendCachedMessages(PEER)
        waitForDelivery()
        val afterFirst = delegate.sent.size

        manager.sendCachedMessages(PEER)
        waitForDelivery()

        assertEquals("A second connect with no new mail must send nothing more", afterFirst, delegate.sent.size)
    }

    @Test
    fun `trimming delivery state drops the oldest rather than all of it`() {
        repeat(StoreForwardManager.MAX_DELIVERED_MESSAGE_IDS + 50) { index ->
            manager.markMessageAsDelivered("delivered-$index")
        }

        manager.forceCleanup()

        // Wiping the set bounded memory by forgetting which messages had
        // already been handed over.
        val debug = manager.getDebugInfo()
        assertTrue(
            "Delivery state must be trimmed to the cap, not emptied: $debug",
            debug.contains("Delivered Messages: ${StoreForwardManager.MAX_DELIVERED_MESSAGE_IDS}")
        )
    }

    @Test
    fun `the newest delivery records are the ones kept`() {
        repeat(StoreForwardManager.MAX_DELIVERED_MESSAGE_IDS + 10) { index ->
            manager.markMessageAsDelivered("delivered-$index")
        }
        manager.forceCleanup()

        // A message delivered a moment ago must not be the one forgotten.
        manager.cacheMessage(privateMessage("delivered-${StoreForwardManager.MAX_DELIVERED_MESSAGE_IDS + 9}"),
            "delivered-${StoreForwardManager.MAX_DELIVERED_MESSAGE_IDS + 9}")
        manager.sendCachedMessages(PEER)
        waitForDelivery()

        assertEquals("An already-delivered message must not be queued again", 0, delegate.sent.size)
    }

    private fun waitForDelivery() {
        // sendCachedMessages dispatches on its own scope with a 10ms/message
        // stagger; this is well clear of the single-message case under test.
        Thread.sleep(300)
    }

    private fun privateMessage(id: String): BitchatPacket = BitchatPacket(
        version = 1u,
        type = MessageType.MESSAGE.value,
        senderID = "1111222233334444".hexToBytes(),
        recipientID = PEER.toByteArray(),
        timestamp = 1u,
        payload = id.toByteArray(),
        ttl = 7u
    )

    private class RecordingDelegate : StoreForwardManagerDelegate {
        val sent = mutableListOf<BitchatPacket>()
        override fun isFavorite(peerID: String) = false
        override fun isPeerOnline(peerID: String) = false
        override fun sendPacket(packet: BitchatPacket) {
            synchronized(sent) { sent.add(packet) }
        }
    }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val PEER = "aaaabbbbccccdddd"
    }
}
