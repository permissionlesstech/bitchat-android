package com.bitchat.android.mesh

import android.os.Build
import com.bitchat.android.model.IdentityAnnouncement
import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.model.PeerCapabilities
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import com.bitchat.android.services.meshgraph.MeshGraphService
import com.bitchat.android.util.AppConstants
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class MessageHandlerTest {
    private lateinit var handler: MessageHandler
    private lateinit var delegate: MessageHandlerDelegate

    private val myPeerID = "1111222233334444"
    private val peerID = "aaaabbbbccccdddd"
    private val nickname = "peer"
    private val noiseKey = ByteArray(32) { 0x0B }
    private val signingKey = ByteArray(32) { 0x0A }
    private val signature = ByteArray(64) { 1 }
    private val announceClockSkewToleranceMs = 10 * 60 * 1000L

    @Before
    fun setup() {
        MeshGraphService.resetForTesting()
        handler = MessageHandler(myPeerID, RuntimeEnvironment.getApplication())
        delegate = mock()
        handler.delegate = delegate

        whenever(delegate.getPeerInfo(peerID)).thenReturn(null)
        whenever(delegate.verifyEd25519Signature(any(), any(), any())).thenReturn(true)
        whenever(
            delegate.updatePeerInfoFromVerifiedAnnouncement(
                any(), any(), any(), any(), any(), anyOrNull()
            )
        ).thenReturn(true)
    }

    @After
    fun tearDown() {
        MeshGraphService.resetForTesting()
    }

    @Test
    fun `handleAnnounce accepts announce within clock skew tolerance for identity binding`() {
        runBlocking {
            val packet = announcePacket(ageMs = AppConstants.Mesh.STALE_PEER_TIMEOUT_MS + 1_000)

            val result = handler.handleAnnounce(RoutedPacket(packet, peerID, "direct-link"))

            assertTrue("Announce within clock skew tolerance should still store peer identity", result)
            verify(delegate).updatePeerInfoFromVerifiedAnnouncement(
                eq(peerID), eq(nickname), any(), any(), eq(true), isNull()
            )
            verify(delegate).onVerifiedAnnouncementProcessed(peerID)
            verify(delegate).updatePeerIDBinding(eq(peerID), eq(nickname), any(), isNull())
        }
    }

    @Test
    fun `handleAnnounce accepts future announce within clock skew tolerance`() {
        runBlocking {
            val packet = announcePacket(ageMs = -(AppConstants.Mesh.STALE_PEER_TIMEOUT_MS + 1_000))

            val result = handler.handleAnnounce(RoutedPacket(packet, peerID, "direct-link"))

            assertTrue("Future announce within clock skew tolerance should still store peer identity", result)
            verify(delegate).updatePeerInfoFromVerifiedAnnouncement(
                eq(peerID), eq(nickname), any(), any(), eq(true), isNull()
            )
        }
    }

    @Test
    fun `handleAnnounce stores advertised capabilities including unknown bits`() {
        runBlocking {
            val capabilities = PeerCapabilities(
                PeerCapabilities.PRIVATE_MEDIA.rawValue or (1L shl 15)
            )
            val packet = announcePacket(ageMs = 0, capabilities = capabilities)

            val result = handler.handleAnnounce(RoutedPacket(packet, peerID, "direct-link"))

            assertTrue(result)
            verify(delegate).updatePeerInfoFromVerifiedAnnouncement(
                eq(peerID),
                eq(nickname),
                any(),
                any(),
                eq(true),
                eq(capabilities)
            )
        }
    }

    @Test
    fun `handleAnnounce rejects announce older than clock skew tolerance`() {
        runBlocking {
            val packet = announcePacket(ageMs = announceClockSkewToleranceMs + 1_000)

            val result = handler.handleAnnounce(RoutedPacket(packet, peerID, "relay-link"))

            assertFalse("Announce older than clock skew tolerance should not store peer identity", result)
            verify(delegate, never()).updatePeerInfoFromVerifiedAnnouncement(
                any(), any(), any(), any(), any(), anyOrNull()
            )
            verify(delegate, never()).onVerifiedAnnouncementProcessed(any())
            verify(delegate, never()).updatePeerIDBinding(any(), any(), any(), any())
        }
    }

    @Test
    fun `handleAnnounce never promotes capability after invalid signature`() {
        runBlocking {
            whenever(delegate.verifyEd25519Signature(any(), any(), any())).thenReturn(false)
            val packet = announcePacket(ageMs = 0, capabilities = PeerCapabilities.PRIVATE_MEDIA)

            val result = handler.handleAnnounce(RoutedPacket(packet, peerID, "direct-link"))

            assertFalse(result)
            verify(delegate, never()).updatePeerInfoFromVerifiedAnnouncement(
                any(), any(), any(), any(), any(), anyOrNull()
            )
            verify(delegate, never()).onVerifiedAnnouncementProcessed(any())
        }
    }

    @Test
    fun `directed raw private media requires a valid signature`() {
        runBlocking {
            whenever(delegate.getBroadcastRecipient()).thenReturn(SpecialRecipients.BROADCAST)
            whenever(delegate.getPeerNickname(peerID)).thenReturn(nickname)
            whenever(delegate.verifySignature(any(), eq(peerID))).thenReturn(false)
            val file = BitchatFilePacket(
                fileName = "legacy.jpg",
                fileSize = 3,
                mimeType = "image/jpeg",
                content = byteArrayOf(1, 2, 3)
            )
            val unsigned = BitchatPacket(
                version = 2u,
                type = MessageType.FILE_TRANSFER.value,
                senderID = peerID.hexToBytes(),
                recipientID = myPeerID.hexToBytes(),
                timestamp = System.currentTimeMillis().toULong(),
                payload = file.encode()!!,
                signature = null,
                ttl = 7u
            )

            handler.handleMessage(RoutedPacket(unsigned, peerID, "direct-link"))
            handler.handleMessage(
                RoutedPacket(unsigned.copy(signature = ByteArray(64) { 1 }), peerID, "direct-link")
            )

            verify(delegate, never()).onMessageReceived(any())
        }
    }

    @Test
    fun `valid signed directed raw private media remains interoperable`() {
        runBlocking {
            whenever(delegate.getBroadcastRecipient()).thenReturn(SpecialRecipients.BROADCAST)
            whenever(delegate.getPeerNickname(peerID)).thenReturn(nickname)
            whenever(delegate.verifySignature(any(), eq(peerID))).thenReturn(true)
            val file = BitchatFilePacket(
                fileName = "legacy-valid.jpg",
                fileSize = 3,
                mimeType = "image/jpeg",
                content = byteArrayOf(1, 2, 3)
            )
            val packet = BitchatPacket(
                version = 2u,
                type = MessageType.FILE_TRANSFER.value,
                senderID = peerID.hexToBytes(),
                recipientID = myPeerID.hexToBytes(),
                timestamp = System.currentTimeMillis().toULong(),
                payload = file.encode()!!,
                signature = signature,
                ttl = 7u
            )

            handler.handleMessage(RoutedPacket(packet, peerID, "direct-link"))

            verify(delegate).verifySignature(packet, peerID)
            verify(delegate).onMessageReceived(any())
        }
    }

    @Test
    fun `encrypted prerelease iOS Noise 0x09 private media is delivered`() {
        runBlocking {
            val file = BitchatFilePacket(
                fileName = "prerelease-ios.pdf",
                fileSize = 4,
                mimeType = "application/pdf",
                content = byteArrayOf(1, 2, 3, 4)
            )
            val prereleasePlaintext = byteArrayOf(0x09) + file.encode()!!
            val ciphertext = byteArrayOf(0x41, 0x42, 0x43)
            whenever(delegate.decryptFromPeer(any(), eq(peerID))).thenReturn(prereleasePlaintext)
            whenever(delegate.getPeerNickname(peerID)).thenReturn(nickname)
            whenever(delegate.getMyNickname()).thenReturn("me")
            whenever(delegate.encryptForPeer(any(), eq(peerID))).thenReturn(byteArrayOf(0x55))

            val outerPacket = BitchatPacket(
                version = 2u,
                type = MessageType.NOISE_ENCRYPTED.value,
                senderID = peerID.hexToBytes(),
                recipientID = myPeerID.hexToBytes(),
                timestamp = System.currentTimeMillis().toULong(),
                payload = ciphertext,
                signature = signature,
                ttl = 7u
            )

            handler.handleNoiseEncrypted(RoutedPacket(outerPacket, peerID, "direct-link"))

            verify(delegate).decryptFromPeer(ciphertext, peerID)
            verify(delegate).onMessageReceived(any())
        }
    }

    private fun announcePacket(
        ageMs: Long,
        ttl: UByte = (AppConstants.MESSAGE_TTL_HOPS.toInt() - 1).toUByte(),
        capabilities: PeerCapabilities? = null
    ): BitchatPacket {
        val announcement = IdentityAnnouncement(
            nickname = nickname,
            noisePublicKey = noiseKey,
            signingPublicKey = signingKey,
            capabilities = capabilities
        )
        return BitchatPacket(
            version = 1u,
            type = MessageType.ANNOUNCE.value,
            senderID = peerID.hexToBytes(),
            recipientID = SpecialRecipients.BROADCAST,
            timestamp = (System.currentTimeMillis() - ageMs).toULong(),
            payload = announcement.encode()!!,
            signature = signature,
            ttl = ttl
        )
    }

    private fun String.hexToBytes(): ByteArray {
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
