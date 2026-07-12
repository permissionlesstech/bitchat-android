package com.bitchat.android.mesh

import android.os.Build
import com.bitchat.android.model.IdentityAnnouncement
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.noise.NoisePeerIdentity
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
        whenever(delegate.updatePeerInfo(any(), any(), any(), any(), any())).thenReturn(true)
    }

    @After
    fun tearDown() {
        MeshGraphService.resetForTesting()
    }

    @Test
    fun `handleAnnounce accepts announce within clock skew tolerance for identity binding`() = runBlocking {
        val packet = announcePacket(ageMs = AppConstants.Mesh.STALE_PEER_TIMEOUT_MS + 1_000)

        val result = handler.handleAnnounce(RoutedPacket(packet, peerID, "direct-link"))

        assertTrue("Announce within clock skew tolerance should still store peer identity", result)
        verify(delegate).updatePeerInfo(eq(peerID), eq(nickname), any(), any(), eq(true))
        Unit
    }

    @Test
    fun `handleAnnounce accepts future announce within clock skew tolerance`() = runBlocking {
        val packet = announcePacket(ageMs = -(AppConstants.Mesh.STALE_PEER_TIMEOUT_MS + 1_000))

        val result = handler.handleAnnounce(RoutedPacket(packet, peerID, "direct-link"))

        assertTrue("Future announce within clock skew tolerance should still store peer identity", result)
        verify(delegate).updatePeerInfo(eq(peerID), eq(nickname), any(), any(), eq(true))
        Unit
    }

    @Test
    fun `handleAnnounce rejects announce older than clock skew tolerance`() = runBlocking {
        val packet = announcePacket(ageMs = announceClockSkewToleranceMs + 1_000)

        val result = handler.handleAnnounce(RoutedPacket(packet, peerID, "relay-link"))

        assertFalse("Announce older than clock skew tolerance should not store peer identity", result)
        verify(delegate, never()).updatePeerInfo(any(), any(), any(), any(), any())
        Unit
    }

    @Test
    fun `first self-signed announce cannot claim an ID derived from another Noise key`() = runBlocking {
        val attackerNoiseKey = ByteArray(32) { 0x6B }
        val packet = announcePacket(ageMs = 0, noisePublicKey = attackerNoiseKey)

        val result = handler.handleAnnounce(RoutedPacket(packet, peerID, "direct-link"))

        assertFalse("A valid self-signature cannot bind an attacker key to a victim ID", result)
        verify(delegate, never()).verifyEd25519Signature(any(), any(), any())
        verify(delegate, never()).updatePeerInfo(any(), any(), any(), any(), any())
        Unit
    }

    @Test
    fun `announce requires a 32-byte Noise static key before peer update`() = runBlocking {
        val malformedNoiseKey = ByteArray(31) { 0x0B }
        val packet = announcePacket(ageMs = 0, noisePublicKey = malformedNoiseKey)

        val result = handler.handleAnnounce(RoutedPacket(packet, peerID, "direct-link"))

        assertFalse(result)
        verify(delegate, never()).verifyEd25519Signature(any(), any(), any())
        verify(delegate, never()).updatePeerInfo(any(), any(), any(), any(), any())
        Unit
    }

    @Test
    fun `announce packet sender cannot be processed under a different routed peer ID`() = runBlocking {
        val otherPeerID = NoisePeerIdentity.derivePeerID(ByteArray(32) { 0x21 })!!
        val packet = announcePacket(ageMs = 0)

        val result = handler.handleAnnounce(RoutedPacket(packet, otherPeerID, "relay-link"))

        assertFalse(result)
        verify(delegate, never()).verifyEd25519Signature(any(), any(), any())
        verify(delegate, never()).updatePeerInfo(any(), any(), any(), any(), any())
        Unit
    }

    @Test
    fun `known peer signing key cannot be replaced without bound Noise session`() = runBlocking {
        whenever(delegate.getPeerInfo(peerID)).thenReturn(peerInfo(signingPublicKey = ByteArray(32) { 0x44 }))
        whenever(delegate.hasNoiseSession(peerID)).thenReturn(false)
        val packet = announcePacket(ageMs = 0)

        val result = handler.handleAnnounce(RoutedPacket(packet, peerID, "direct-link"))

        assertFalse(result)
        verify(delegate, never()).updatePeerInfo(any(), any(), any(), any(), any())
        Unit
    }

    @Test
    fun `ambient bound Noise session does not authorize signing key replacement`() = runBlocking {
        whenever(delegate.getPeerInfo(peerID)).thenReturn(peerInfo(signingPublicKey = ByteArray(32) { 0x44 }))
        whenever(delegate.hasNoiseSession(peerID)).thenReturn(true)
        val packet = announcePacket(ageMs = 0)

        val result = handler.handleAnnounce(RoutedPacket(packet, peerID, "direct-link"))

        assertFalse(result)
        verify(delegate, never()).updatePeerInfo(any(), any(), any(), any(), any())
        Unit
    }

    private fun announcePacket(
        ageMs: Long,
        ttl: UByte = (AppConstants.MESSAGE_TTL_HOPS.toInt() - 1).toUByte(),
        noisePublicKey: ByteArray = noiseKey
    ): BitchatPacket {
        val announcement = IdentityAnnouncement(
            nickname = nickname,
            noisePublicKey = noisePublicKey,
            signingPublicKey = signingKey
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
