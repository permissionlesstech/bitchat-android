package com.bitchat.android.sync

import com.bitchat.android.model.RequestSyncPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GossipSyncLoadTest {
    @Test
    fun `identical full caches do not resend packets omitted from the advertised window`() = runTest {
        val sent = mutableListOf<BitchatPacket>()
        val manager = fixtureManager(this, sent)
        val build = GossipSyncManager::class.java.getDeclaredMethod("buildGcsPayload").apply { isAccessible = true }
        val request = RequestSyncPacket.decode(build.invoke(manager) as ByteArray)!!
        manager.handleRequestSync("5555666677778888", request)
        advanceUntilIdle()
        assertTrue("Identical caches must converge to no response traffic", sent.isEmpty())
    }

    @Test
    fun `empty requester receives a bounded paced recent window`() = runTest {
        val sent = mutableListOf<BitchatPacket>()
        val manager = fixtureManager(this, sent)
        manager.handleRequestSync("5555666677778888", RequestSyncPacket(8, 1, ByteArray(0)))
        runCurrent()
        assertEquals("Do not submit the whole history before transport callbacks can run", 1, sent.size)
        advanceUntilIdle()
        assertTrue(sent.size in 1..355)
        assertTrue(testScheduler.currentTime >= (sent.size - 1) * 20L)
    }

    @Test
    fun `fresh requester receives older identity prerequisite before recent messages in chronological order`() = runTest {
        val sent = mutableListOf<BitchatPacket>()
        val manager = fixtureManager(this, sent)
        manager.clear()
        val now = System.currentTimeMillis()
        val sender = ByteArray(8) { 7 }
        manager.onPublicPacketSeen(BitchatPacket(
            type = MessageType.ANNOUNCE.value, senderID = sender,
            timestamp = now.toULong(), payload = byteArrayOf(1), ttl = 1u
        ))
        repeat(500) { index ->
            manager.onPublicPacketSeen(BitchatPacket(
                type = MessageType.MESSAGE.value, senderID = sender,
                timestamp = (now + index + 1).toULong(), payload = byteArrayOf(2), ttl = 1u
            ))
        }
        manager.handleRequestSync("5555666677778888", RequestSyncPacket(8, 1, ByteArray(0)))
        advanceUntilIdle()
        assertEquals(MessageType.ANNOUNCE.value, sent.first().type)
        assertTrue(sent.size in 2..355)
        val messages = sent.drop(1)
        assertTrue(messages.all { it.type == MessageType.MESSAGE.value })
        assertEquals(messages.map { it.timestamp }.sorted(), messages.map { it.timestamp })
        assertEquals((now + 500).toULong(), messages.last().timestamp)
        // The exact dependency-closed window must also converge after filter encoding.
        val build = GossipSyncManager::class.java.getDeclaredMethod("buildGcsPayload").apply { isAccessible = true }
        val request = RequestSyncPacket.decode(build.invoke(manager) as ByteArray)!!
        sent.clear()
        manager.handleRequestSync("5555666677778888", request)
        advanceUntilIdle()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `many senders retain their announcement prerequisite within the response budget`() = runTest {
        val sent = mutableListOf<BitchatPacket>()
        val manager = fixtureManager(this, sent)
        manager.handleRequestSync("5555666677778888", RequestSyncPacket(8, 1, ByteArray(0)))
        advanceUntilIdle()
        val announced = mutableSetOf<List<Byte>>()
        for (packet in sent) {
            if (packet.type == MessageType.ANNOUNCE.value) announced.add(packet.senderID.toList())
            else assertTrue("Message sender must already have been announced", packet.senderID.toList() in announced)
        }
        assertTrue(sent.any { it.type == MessageType.MESSAGE.value })
    }

    private fun fixtureManager(scope: kotlinx.coroutines.CoroutineScope, sent: MutableList<BitchatPacket>): GossipSyncManager {
        val manager = GossipSyncManager("1111222233334444", scope, object : GossipSyncManager.ConfigProvider {
            override fun seenCapacity() = 500
            override fun gcsMaxBytes() = 400
            override fun gcsTargetFpr() = 0.01
        })
        manager.delegate = object : GossipSyncManager.Delegate {
            override fun sendPacket(packet: BitchatPacket) = Unit
            override fun sendPacketToPeer(peerID: String, packet: BitchatPacket) { sent.add(packet) }
            override fun signPacketForBroadcast(packet: BitchatPacket) = packet
        }
        val now = System.currentTimeMillis()
        repeat(500) { index ->
            val sender = ByteArray(8) { offset -> ((index.toLong() + 1) ushr (offset * 8)).toByte() }
            for (type in listOf(MessageType.MESSAGE, MessageType.ANNOUNCE)) {
                manager.onPublicPacketSeen(BitchatPacket(
                    type = type.value, senderID = sender, timestamp = (now + index).toULong(),
                    payload = "fixture-$index".toByteArray(), ttl = 1u
                ))
            }
        }
        return manager
    }
}
