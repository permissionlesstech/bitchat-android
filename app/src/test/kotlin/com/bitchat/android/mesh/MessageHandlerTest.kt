package com.bitchat.android.mesh

import android.os.Build
import com.bitchat.android.model.IdentityAnnouncement
import com.bitchat.android.model.AuthenticatedPeerState
import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.model.NoisePayload
import com.bitchat.android.model.NoisePayloadType
import com.bitchat.android.model.PeerCapabilities
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.noise.NoisePeerIdentity
import com.bitchat.android.noise.AuthenticatedNoiseSession
import com.bitchat.android.noise.NoiseDecryptionResult
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
    private val noiseKey = ByteArray(32) { 0x0B }
    private val peerID = NoisePeerIdentity.derivePeerID(noiseKey)!!
    private val authenticatedSession = AuthenticatedNoiseSession(
        noiseKey,
        ByteArray(32) { 0x5D }
    )
    private val nickname = "peer"
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
                eq(peerID), eq(nickname), any(), any(), eq(true), anyOrNull()
            )
        }
    }

    @Test
    fun `handleAnnounce accepts future announce within clock skew tolerance`() {
        runBlocking {
            val packet = announcePacket(ageMs = -(AppConstants.Mesh.STALE_PEER_TIMEOUT_MS + 1_000))

            val result = handler.handleAnnounce(RoutedPacket(packet, peerID, "direct-link"))

            assertTrue("Future announce within clock skew tolerance should still store peer identity", result)
            verify(delegate).updatePeerInfoFromVerifiedAnnouncement(
                eq(peerID), eq(nickname), any(), any(), eq(true), anyOrNull()
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
            whenever(delegate.decryptFromPeer(any(), eq(peerID))).thenReturn(
                NoiseDecryptionResult(prereleasePlaintext, authenticatedSession)
            )
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

    @Test
    fun `valid encrypted peer state is delivered and malformed state is ignored`() = runBlocking {
        val state = AuthenticatedPeerState(PeerCapabilities.PRIVATE_MEDIA, signingKey)
        val validCiphertext = byteArrayOf(0x31)
        val malformedCiphertext = byteArrayOf(0x32)
        whenever(delegate.decryptFromPeer(validCiphertext, peerID)).thenReturn(
            NoiseDecryptionResult(
                NoisePayload(NoisePayloadType.PEER_STATE, state.encode()).encode(),
                authenticatedSession
            )
        )
        whenever(delegate.decryptFromPeer(malformedCiphertext, peerID)).thenReturn(
            NoiseDecryptionResult(
                NoisePayload(NoisePayloadType.PEER_STATE, byteArrayOf(0x01, 0x01)).encode(),
                authenticatedSession
            )
        )
        val base = BitchatPacket(
            version = 1u,
            type = MessageType.NOISE_ENCRYPTED.value,
            senderID = peerID.hexToBytes(),
            recipientID = myPeerID.hexToBytes(),
            timestamp = System.currentTimeMillis().toULong(),
            payload = validCiphertext,
            ttl = 7u
        )

        handler.handleNoiseEncrypted(RoutedPacket(base, peerID, "direct-link"))
        handler.handleNoiseEncrypted(
            RoutedPacket(base.copy(payload = malformedCiphertext), peerID, "direct-link")
        )

        verify(delegate).onAuthenticatedPeerStateReceived(peerID, state, authenticatedSession)
    }

    @Test
    fun `first self-signed announce cannot claim an ID derived from another Noise key`() = runBlocking {
        val attackerNoiseKey = ByteArray(32) { 0x6B }
        val packet = announcePacket(ageMs = 0, noisePublicKey = attackerNoiseKey)

        val result = handler.handleAnnounce(RoutedPacket(packet, peerID, "direct-link"))

        assertFalse("A valid self-signature cannot bind an attacker key to a victim ID", result)
        verify(delegate, never()).verifyEd25519Signature(any(), any(), any())
        verify(delegate, never()).updatePeerInfoFromVerifiedAnnouncement(
            any(), any(), any(), any(), any(), anyOrNull()
        )
        Unit
    }

    @Test
    fun `announce requires a 32-byte Noise static key before peer update`() = runBlocking {
        val malformedNoiseKey = ByteArray(31) { 0x0B }
        val packet = announcePacket(ageMs = 0, noisePublicKey = malformedNoiseKey)

        val result = handler.handleAnnounce(RoutedPacket(packet, peerID, "direct-link"))

        assertFalse(result)
        verify(delegate, never()).verifyEd25519Signature(any(), any(), any())
        verify(delegate, never()).updatePeerInfoFromVerifiedAnnouncement(
            any(), any(), any(), any(), any(), anyOrNull()
        )
        Unit
    }

    @Test
    fun `announce packet sender cannot be processed under a different routed peer ID`() = runBlocking {
        val otherPeerID = NoisePeerIdentity.derivePeerID(ByteArray(32) { 0x21 })!!
        val packet = announcePacket(ageMs = 0)

        val result = handler.handleAnnounce(RoutedPacket(packet, otherPeerID, "relay-link"))

        assertFalse(result)
        verify(delegate, never()).verifyEd25519Signature(any(), any(), any())
        verify(delegate, never()).updatePeerInfoFromVerifiedAnnouncement(
            any(), any(), any(), any(), any(), anyOrNull()
        )
        Unit
    }

    @Test
    fun `known peer signing key cannot be replaced without bound Noise session`() = runBlocking {
        whenever(delegate.getPeerInfo(peerID)).thenReturn(peerInfo(signingPublicKey = ByteArray(32) { 0x44 }))
        whenever(delegate.hasNoiseSession(peerID)).thenReturn(false)
        val packet = announcePacket(ageMs = 0)

        val result = handler.handleAnnounce(RoutedPacket(packet, peerID, "direct-link"))

        assertFalse(result)
        verify(delegate, never()).updatePeerInfoFromVerifiedAnnouncement(
            any(), any(), any(), any(), any(), anyOrNull()
        )
        Unit
    }

    @Test
    fun `ambient bound Noise session does not authorize signing key replacement`() = runBlocking {
        whenever(delegate.getPeerInfo(peerID)).thenReturn(peerInfo(signingPublicKey = ByteArray(32) { 0x44 }))
        whenever(delegate.hasNoiseSession(peerID)).thenReturn(true)
        val packet = announcePacket(ageMs = 0)

        val result = handler.handleAnnounce(RoutedPacket(packet, peerID, "direct-link"))

        assertFalse(result)
        verify(delegate, never()).updatePeerInfoFromVerifiedAnnouncement(
            any(), any(), any(), any(), any(), anyOrNull()
        )
        Unit
    }

    @Test
    fun `persisted authenticated Ed key rejects copied-static preannounce after restart`() = runBlocking {
        whenever(delegate.getAuthenticatedSigningKey(any())).thenReturn(ByteArray(32) { 0x44 })
        val packet = announcePacket(ageMs = 0)

        val result = handler.handleAnnounce(RoutedPacket(packet, peerID, "direct-link"))

        assertFalse(result)
        verify(delegate, never()).updatePeerInfoFromVerifiedAnnouncement(
            any(), any(), any(), any(), any(), anyOrNull()
        )
        Unit
    }

    @Test
    fun `repeated decrypt failures reset stale session and re-handshake`() = runBlocking {
        whenever(delegate.decryptFromPeer(any(), eq(peerID))).thenReturn(null)
        whenever(delegate.hasNoiseSession(peerID)).thenReturn(true)
        val packet = encryptedPacket()

        repeat(2) {
            assertFalse(handler.handleNoiseEncrypted(RoutedPacket(packet, peerID, "direct-link")))
        }
        verify(delegate, never()).removeNoiseSession(any())
        verify(delegate, never()).initiateNoiseHandshake(any())

        assertFalse(handler.handleNoiseEncrypted(RoutedPacket(packet, peerID, "direct-link")))
        verify(delegate).removeNoiseSession(peerID)
        verify(delegate).initiateNoiseHandshake(peerID)
    }

    @Test
    fun `successful decrypt resets the failure counter`() = runBlocking {
        whenever(delegate.hasNoiseSession(peerID)).thenReturn(true)
        val plaintext = NoisePayload(NoisePayloadType.DELIVERED, "id-1".toByteArray()).encode()
        whenever(delegate.decryptFromPeer(any(), eq(peerID)))
            .thenReturn(null)
            .thenReturn(null)
            .thenReturn(NoiseDecryptionResult(plaintext, authenticatedSession))
            .thenReturn(null)
            .thenReturn(null)
        val packet = encryptedPacket()

        repeat(5) {
            handler.handleNoiseEncrypted(RoutedPacket(packet, peerID, "direct-link"))
        }

        verify(delegate, never()).removeNoiseSession(any())
        verify(delegate, never()).initiateNoiseHandshake(any())
    }

    @Test
    fun `decrypt failures without an established session never reset`() = runBlocking {
        whenever(delegate.decryptFromPeer(any(), eq(peerID))).thenReturn(null)
        whenever(delegate.hasNoiseSession(peerID)).thenReturn(false)
        val packet = encryptedPacket()

        repeat(4) {
            assertFalse(handler.handleNoiseEncrypted(RoutedPacket(packet, peerID, "direct-link")))
        }

        verify(delegate, never()).removeNoiseSession(any())
        verify(delegate, never()).initiateNoiseHandshake(any())
    }

    @Test
    fun `successful decrypt reports the packet as valid`() = runBlocking {
        val plaintext = NoisePayload(NoisePayloadType.DELIVERED, "id-2".toByteArray()).encode()
        whenever(delegate.decryptFromPeer(any(), eq(peerID))).thenReturn(
            NoiseDecryptionResult(plaintext, authenticatedSession)
        )

        assertTrue(handler.handleNoiseEncrypted(RoutedPacket(encryptedPacket(), peerID, "direct-link")))
    }

    private fun encryptedPacket(): BitchatPacket = BitchatPacket(
        version = 1u,
        type = MessageType.NOISE_ENCRYPTED.value,
        senderID = peerID.hexToBytes(),
        recipientID = myPeerID.hexToBytes(),
        timestamp = System.currentTimeMillis().toULong(),
        payload = byteArrayOf(0x41, 0x42, 0x43),
        ttl = 7u
    )

    private fun announcePacket(
        ageMs: Long,
        ttl: UByte = (AppConstants.MESSAGE_TTL_HOPS.toInt() - 1).toUByte(),
        capabilities: PeerCapabilities? = null,
        noisePublicKey: ByteArray = noiseKey
    ): BitchatPacket {
        val announcement = IdentityAnnouncement(
            nickname = nickname,
            noisePublicKey = noisePublicKey,
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

    private fun peerInfo(signingPublicKey: ByteArray) = PeerInfo(
        id = peerID,
        nickname = nickname,
        isConnected = true,
        isDirectConnection = true,
        noisePublicKey = noiseKey,
        signingPublicKey = signingPublicKey,
        isVerifiedNickname = true,
        lastSeen = System.currentTimeMillis()
    )
}
