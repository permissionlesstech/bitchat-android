package com.bitchat.android.mesh

import com.bitchat.android.model.NoisePayload
import com.bitchat.android.model.NoisePayloadType
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MessageHandlerNdrTest {

    @Test
    fun handleNoiseEncryptedForwardsNdrPayloadToDelegate() {
        val delegate = FakeDelegate()
        val handler = MessageHandler(
            myPeerID = "0011223344556677",
            appContext = ApplicationProvider.getApplicationContext()
        )
        handler.delegate = delegate

        val payload = NoisePayload(
            type = NoisePayloadType.NDR_EVENT,
            data = """{"id":"invite1","kind":30078}""".toByteArray()
        ).encode()
        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.NOISE_ENCRYPTED.value,
            senderID = byteArrayOf(0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17),
            recipientID = byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77),
            timestamp = 123uL,
            payload = payload,
            signature = null,
            ttl = 7u
        )

        kotlinx.coroutines.runBlocking {
            handler.handleNoiseEncrypted(RoutedPacket(packet = packet, peerID = "1011121314151617"))
        }

        assertEquals("1011121314151617", delegate.ndrPeerID)
        assertEquals("""{"id":"invite1","kind":30078}""", delegate.ndrPayload)
        assertEquals(123L, delegate.ndrTimestampMs)
    }

    @Test
    fun handleNoiseEncryptedReplaysQueuedPayloadAfterHandshake() {
        val delegate = FakeDelegate().apply {
            hasSession = false
            decryptReturnsNull = true
        }
        val handler = MessageHandler(
            myPeerID = "0011223344556677",
            appContext = ApplicationProvider.getApplicationContext()
        )
        handler.delegate = delegate

        val payload = NoisePayload(
            type = NoisePayloadType.NDR_EVENT,
            data = """{"id":"invite2","kind":30078}""".toByteArray()
        ).encode()
        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.NOISE_ENCRYPTED.value,
            senderID = byteArrayOf(0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17),
            recipientID = byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77),
            timestamp = 456uL,
            payload = payload,
            signature = null,
            ttl = 7u
        )

        kotlinx.coroutines.runBlocking {
            handler.handleNoiseEncrypted(RoutedPacket(packet = packet, peerID = "1011121314151617"))
        }

        assertNull(delegate.ndrPeerID)

        delegate.hasSession = true
        delegate.decryptReturnsNull = false

        kotlinx.coroutines.runBlocking {
            handler.flushPendingNoiseEncrypted("1011121314151617")
        }

        assertEquals("1011121314151617", delegate.ndrPeerID)
        assertEquals("""{"id":"invite2","kind":30078}""", delegate.ndrPayload)
        assertEquals(456L, delegate.ndrTimestampMs)
    }

    private class FakeDelegate : MessageHandlerDelegate {
        var ndrPeerID: String? = null
        var ndrPayload: String? = null
        var ndrTimestampMs: Long? = null
        var hasSession: Boolean = true
        var decryptReturnsNull: Boolean = false

        override fun addOrUpdatePeer(peerID: String, nickname: String): Boolean = false
        override fun removePeer(peerID: String) = Unit
        override fun updatePeerNickname(peerID: String, nickname: String) = Unit
        override fun getPeerNickname(peerID: String): String? = null
        override fun getNetworkSize(): Int = 0
        override fun getMyNickname(): String? = null
        override fun getPeerInfo(peerID: String): PeerInfo? = null
        override fun updatePeerInfo(
            peerID: String,
            nickname: String,
            noisePublicKey: ByteArray,
            signingPublicKey: ByteArray,
            isVerified: Boolean
        ): Boolean = false
        override fun sendPacket(packet: BitchatPacket) = Unit
        override fun relayPacket(routed: RoutedPacket) = Unit
        override fun getBroadcastRecipient(): ByteArray = ByteArray(0)
        override fun verifySignature(packet: BitchatPacket, peerID: String): Boolean = true
        override fun encryptForPeer(data: ByteArray, recipientPeerID: String): ByteArray? = data
        override fun decryptFromPeer(encryptedData: ByteArray, senderPeerID: String): ByteArray? =
            if (decryptReturnsNull) null else encryptedData
        override fun verifyEd25519Signature(signature: ByteArray, data: ByteArray, publicKey: ByteArray): Boolean = true
        override fun hasNoiseSession(peerID: String): Boolean = hasSession
        override fun initiateNoiseHandshake(peerID: String) = Unit
        override fun processNoiseHandshakeMessage(payload: ByteArray, peerID: String): ByteArray? = null
        override fun updatePeerIDBinding(newPeerID: String, nickname: String, publicKey: ByteArray, previousPeerID: String?) = Unit
        override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? = null
        override fun onMessageReceived(message: com.bitchat.android.model.BitchatMessage) = Unit
        override fun onChannelLeave(channel: String, fromPeer: String) = Unit
        override fun onDeliveryAckReceived(messageID: String, peerID: String) = Unit
        override fun onReadReceiptReceived(messageID: String, peerID: String) = Unit
        override fun onVerifyChallengeReceived(peerID: String, payload: ByteArray, timestampMs: Long) = Unit
        override fun onVerifyResponseReceived(peerID: String, payload: ByteArray, timestampMs: Long) = Unit
        override fun onNdrEventReceived(peerID: String, payload: ByteArray, timestampMs: Long) {
            ndrPeerID = peerID
            ndrPayload = String(payload)
            ndrTimestampMs = timestampMs
        }
    }
}
