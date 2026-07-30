package com.bitchat.android.mesh

import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PacketProcessorAnnounceSideEffectTest {
    private val processors = mutableListOf<PacketProcessor>()

    @After
    fun tearDown() {
        processors.forEach(PacketProcessor::shutdown)
    }

    @Test
    fun `rejected announce does not update last seen or relay`() = runBlocking {
        val delegate = RecordingDelegate(acceptAnnounce = false)
        val processor = processor(delegate)

        processor.processPacket(announce())
        withTimeout(1_000) { delegate.handled.await() }

        assertNull(withTimeoutOrNull(250) { delegate.lastSeen.await() })
        assertEquals(0, delegate.relayCount)
    }

    @Test
    fun `accepted announce unlocks post-handler side effects`() = runBlocking {
        val delegate = RecordingDelegate(acceptAnnounce = true)
        val processor = processor(delegate)

        processor.processPacket(announce())

        assertEquals(PEER_ID, withTimeout(1_000) { delegate.lastSeen.await() })
    }

    @Test
    fun `rejected handshake does not update last seen`() = runBlocking {
        val delegate = RecordingDelegate(acceptAnnounce = true, acceptHandshake = false)
        val processor = processor(delegate)

        processor.processPacket(handshake())
        withTimeout(1_000) { delegate.handshakeHandled.await() }

        assertNull(withTimeoutOrNull(250) { delegate.lastSeen.await() })
    }

    @Test
    fun `accepted handshake updates last seen`() = runBlocking {
        val delegate = RecordingDelegate(acceptAnnounce = true, acceptHandshake = true)
        val processor = processor(delegate)

        processor.processPacket(handshake())

        assertEquals(PEER_ID, withTimeout(1_000) { delegate.lastSeen.await() })
    }

    private fun processor(delegate: RecordingDelegate): PacketProcessor =
        PacketProcessor(MY_PEER_ID).also {
            it.delegate = delegate
            processors += it
        }

    private fun announce(): RoutedPacket {
        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.ANNOUNCE.value,
            senderID = PEER_ID.hexToBytes(),
            recipientID = SpecialRecipients.BROADCAST,
            timestamp = System.currentTimeMillis().toULong(),
            payload = byteArrayOf(0x01),
            ttl = 7u
        )
        return RoutedPacket(packet, PEER_ID, "direct-link")
    }

    private fun handshake(): RoutedPacket {
        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.NOISE_HANDSHAKE.value,
            senderID = PEER_ID.hexToBytes(),
            recipientID = MY_PEER_ID.hexToBytes(),
            timestamp = System.currentTimeMillis().toULong(),
            payload = byteArrayOf(0x01),
            ttl = 7u
        )
        return RoutedPacket(packet, PEER_ID, "direct-link")
    }

    private class RecordingDelegate(
        private val acceptAnnounce: Boolean,
        private val acceptHandshake: Boolean = false
    ) : PacketProcessorDelegate {
        val handled = CompletableDeferred<Unit>()
        val handshakeHandled = CompletableDeferred<Unit>()
        val lastSeen = CompletableDeferred<String>()
        @Volatile var relayCount = 0

        override fun validatePacketSecurity(packet: BitchatPacket, peerID: String) = true
        override fun updatePeerLastSeen(peerID: String) {
            lastSeen.complete(peerID)
        }
        override fun getPeerNickname(peerID: String): String? = null
        override fun getNetworkSize() = 1
        override fun getBroadcastRecipient(): ByteArray = SpecialRecipients.BROADCAST
        override fun handleNoiseHandshake(routed: RoutedPacket): Boolean {
            handshakeHandled.complete(Unit)
            return acceptHandshake
        }
        override fun handleNoiseEncrypted(routed: RoutedPacket) = true
        override suspend fun handleAnnounce(routed: RoutedPacket): Boolean {
            handled.complete(Unit)
            return acceptAnnounce
        }
        override fun handleMessage(routed: RoutedPacket) = Unit
        override fun handleLeave(routed: RoutedPacket) = Unit
        override fun handleFragment(packet: BitchatPacket): BitchatPacket? = null
        override fun handleRequestSync(routed: RoutedPacket) = Unit
        override fun sendAnnouncementToPeer(peerID: String) = Unit
        override fun sendCachedMessages(peerID: String) = Unit
        override fun relayPacket(routed: RoutedPacket) {
            relayCount += 1
        }
        override fun sendToPeer(peerID: String, routed: RoutedPacket) = false
    }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val MY_PEER_ID = "1111222233334444"
        const val PEER_ID = "aaaabbbbccccdddd"
    }
}
