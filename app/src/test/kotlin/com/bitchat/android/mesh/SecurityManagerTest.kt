package com.bitchat.android.mesh

import android.os.Build
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.model.IdentityAnnouncement
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.noise.NoiseHandshakeProcessingResult
import com.bitchat.android.noise.NoisePeerIdentity
import com.bitchat.android.noise.NoiseSessionError
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class SecurityManagerTest {

    private lateinit var securityManager: SecurityManager
    private lateinit var fakeEncryptionService: FakeEncryptionService
    private lateinit var mockDelegate: SecurityManagerDelegate
    
    private val myPeerID = "1111222233334444"
    private val otherPeerID = "aaaabbbbccccdddd"
    // Key pairs (using dummy bytes for mock verification)
    private val otherSigningKey = ByteArray(32) { 0xA }
    private val otherNoiseKey = ByteArray(32) { 0xB }
    private val sessionToken = ByteArray(32) { 0x5C }
    private val unknownPeerID = NoisePeerIdentity.derivePeerID(otherNoiseKey)!!

    private val dummyPayload = "Hello World".toByteArray()
    private val validSignature = ByteArray(64) { 1 }
    private val invalidSignature = ByteArray(64) { 0 }

    // Fake implementation to bypass initialization issues in tests
    open class FakeEncryptionService : EncryptionService(RuntimeEnvironment.getApplication()) {
        var shouldVerify: Boolean = true
        var lastVerifySignature: ByteArray? = null
        var lastVerifyData: ByteArray? = null
        var lastVerifyKey: ByteArray? = null
        var handshakeResult = NoiseHandshakeProcessingResult(null, false)
        var handshakeError: Exception? = null
        var handshakeCalls = 0
        var removePeerCalls = 0

        override fun initialize() {
            // Do nothing to avoid KeyStore access in tests
        }

        override fun verifyEd25519Signature(signature: ByteArray, data: ByteArray, publicKeyBytes: ByteArray): Boolean {
            lastVerifySignature = signature
            lastVerifyData = data
            lastVerifyKey = publicKeyBytes
            
            // Simple logic: if configured to verify, check if signature matches validSignature
            // We use the signature bytes passed in setup()
            if (shouldVerify) {
                 return signature.contentEquals(byteArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1))
            }
            return false
        }

        override fun processHandshakeMessageWithResult(
            data: ByteArray,
            peerID: String
        ): NoiseHandshakeProcessingResult {
            handshakeCalls += 1
            handshakeError?.let { throw it }
            return handshakeResult
        }

        override fun removePeer(peerID: String) {
            removePeerCalls += 1
        }
    }

    @Before
    fun setup() {
        fakeEncryptionService = FakeEncryptionService()
        mockDelegate = mock()
        
        securityManager = SecurityManager(fakeEncryptionService, myPeerID)
        securityManager.delegate = mockDelegate
    }

    @After
    fun tearDown() {
        if (::securityManager.isInitialized) {
            securityManager.shutdown()
        }
    }

    @Test
    fun `validatePacket - rejects packet with missing signature`() {
        val packet = BitchatPacket(
            type = MessageType.MESSAGE.value,
            ttl = 10u,
            senderID = otherPeerID,
            payload = dummyPayload
        )
        packet.signature = null

        val result = securityManager.validatePacket(packet, otherPeerID)
        
        assertFalse("Packet without signature should be rejected", result)
    }

    @Test
    fun `verifySignature - verifies canonical packet with announced signing key`() {
        setupKnownPeer(otherPeerID, otherSigningKey)
        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.FILE_TRANSFER.value,
            senderID = MeshPacketUtils.hexStringToByteArray(otherPeerID),
            recipientID = MeshPacketUtils.hexStringToByteArray(myPeerID),
            timestamp = System.currentTimeMillis().toULong(),
            payload = dummyPayload,
            signature = validSignature,
            ttl = 10u
        )

        assertTrue(securityManager.verifySignature(packet, otherPeerID))
        assertTrue(fakeEncryptionService.lastVerifySignature.contentEquals(validSignature))
        assertTrue(fakeEncryptionService.lastVerifyData.contentEquals(packet.toBinaryDataForSigning()))
        assertTrue(fakeEncryptionService.lastVerifyKey.contentEquals(otherSigningKey))
    }

    @Test
    fun `verifySignature - rejects missing signature and unknown signing key`() {
        val unsigned = BitchatPacket(
            version = 1u,
            type = MessageType.FILE_TRANSFER.value,
            senderID = MeshPacketUtils.hexStringToByteArray(otherPeerID),
            recipientID = MeshPacketUtils.hexStringToByteArray(myPeerID),
            timestamp = System.currentTimeMillis().toULong(),
            payload = dummyPayload,
            ttl = 10u
        )
        assertFalse(securityManager.verifySignature(unsigned, otherPeerID))

        unsigned.signature = validSignature
        whenever(mockDelegate.getPeerInfo(otherPeerID)).thenReturn(null)
        assertFalse(securityManager.verifySignature(unsigned, otherPeerID))
    }

    @Test
    fun `validatePacket - rejects packet with invalid signature`() {
        setupKnownPeer(otherPeerID, otherSigningKey)
        
        val packet = BitchatPacket(
            type = MessageType.MESSAGE.value,
            ttl = 10u,
            senderID = otherPeerID,
            payload = dummyPayload
        )
        packet.signature = invalidSignature

        val result = securityManager.validatePacket(packet, otherPeerID)
        
        assertFalse("Packet with invalid signature should be rejected", result)
    }

    @Test
    fun `invalid packet does not poison duplicate detection for later valid packet`() {
        setupKnownPeer(otherPeerID, otherSigningKey)
        val packet = BitchatPacket(
            type = MessageType.MESSAGE.value,
            ttl = 10u,
            senderID = otherPeerID,
            payload = dummyPayload
        )
        packet.signature = invalidSignature
        assertFalse(securityManager.validatePacket(packet, otherPeerID))

        packet.signature = validSignature
        assertTrue(securityManager.validatePacket(packet, otherPeerID))
    }

    @Test
    fun `validatePacket - rejects packet from unknown peer (no key)`() {
        whenever(mockDelegate.getPeerInfo(unknownPeerID)).thenReturn(null)
        
        val packet = BitchatPacket(
            type = MessageType.MESSAGE.value,
            ttl = 10u,
            senderID = unknownPeerID,
            payload = dummyPayload
        )
        packet.signature = validSignature

        val result = securityManager.validatePacket(packet, unknownPeerID)
        
        assertFalse("Packet from unknown peer should be rejected (cannot verify signature)", result)
    }

    @Test
    fun `validatePacket - accepts packet with valid signature from known peer`() {
        setupKnownPeer(otherPeerID, otherSigningKey)
        
        val packet = BitchatPacket(
            type = MessageType.MESSAGE.value,
            ttl = 10u,
            senderID = otherPeerID,
            payload = dummyPayload
        )
        packet.signature = validSignature

        val result = securityManager.validatePacket(packet, otherPeerID)
        
        assertTrue("Valid signed packet from known peer should be accepted", result)
    }

    @Test
    fun `validatePacket rejects unsigned and invalidly signed LEAVE packets`() {
        setupKnownPeer(otherPeerID, otherSigningKey)

        val unsigned = BitchatPacket(
            type = MessageType.LEAVE.value,
            ttl = 7u,
            senderID = otherPeerID,
            payload = byteArrayOf()
        )
        assertFalse("Unsigned LEAVE must not evict or relay the claimed peer", securityManager.validatePacket(unsigned, otherPeerID))

        val invalid = BitchatPacket(
            type = MessageType.LEAVE.value,
            ttl = 7u,
            senderID = otherPeerID,
            payload = "forged".toByteArray()
        ).also { it.signature = invalidSignature }
        assertFalse("Bad LEAVE signature must be rejected", securityManager.validatePacket(invalid, otherPeerID))
    }

    @Test
    fun `validatePacket accepts signed LEAVE from known peer`() {
        setupKnownPeer(otherPeerID, otherSigningKey)
        val packet = BitchatPacket(
            type = MessageType.LEAVE.value,
            ttl = 7u,
            senderID = otherPeerID,
            payload = byteArrayOf()
        ).also { it.signature = validSignature }

        assertTrue("A valid signed LEAVE remains wire-compatible", securityManager.validatePacket(packet, otherPeerID))
        assertTrue(fakeEncryptionService.lastVerifyKey.contentEquals(otherSigningKey))
    }

    @Test
    fun `validatePacket rejects signed LEAVE outside replay window`() {
        setupKnownPeer(otherPeerID, otherSigningKey)
        val stale = BitchatPacket(
            type = MessageType.LEAVE.value,
            ttl = 7u,
            senderID = MeshPacketUtils.hexStringToByteArray(otherPeerID),
            timestamp = (System.currentTimeMillis() - 5 * 60 * 1_000L - 1).toULong(),
            payload = byteArrayOf()
        ).also { it.signature = validSignature }
        val future = BitchatPacket(
            type = MessageType.LEAVE.value,
            ttl = 7u,
            senderID = MeshPacketUtils.hexStringToByteArray(otherPeerID),
            timestamp = (System.currentTimeMillis() + 5 * 60 * 1_000L + 1_000).toULong(),
            payload = byteArrayOf()
        ).also { it.signature = validSignature }

        assertFalse("Captured LEAVE must expire even after replay-cache loss", securityManager.validatePacket(stale, otherPeerID))
        assertFalse("Future-dated LEAVE must not extend its replay lifetime", securityManager.validatePacket(future, otherPeerID))
    }

    @Test
    fun `validatePacket - accepts ANNOUNCE packet from unknown peer (extracts key)`() {
        val announcement = IdentityAnnouncement(
            nickname = "New User",
            noisePublicKey = otherNoiseKey,
            signingPublicKey = otherSigningKey
        )
        val payload = announcement.encode()!!
        
        val packet = BitchatPacket(
            type = MessageType.ANNOUNCE.value,
            ttl = 10u,
            senderID = unknownPeerID,
            payload = payload
        )
        packet.signature = validSignature

        whenever(mockDelegate.getPeerInfo(unknownPeerID)).thenReturn(null)
        
        val result = securityManager.validatePacket(packet, unknownPeerID)
        
        assertTrue("ANNOUNCE from unknown peer should be accepted (key extracted from payload)", result)
        // Verify we used the correct key
        assertTrue("Should have used extracted key for verification", 
            fakeEncryptionService.lastVerifyKey.contentEquals(otherSigningKey))
    }

    @Test
    fun `validatePacket - rejects ANNOUNCE packet with invalid signature`() {
        val announcement = IdentityAnnouncement(
            nickname = "New User",
            noisePublicKey = otherNoiseKey,
            signingPublicKey = otherSigningKey
        )
        val payload = announcement.encode()!!
        
        val packet = BitchatPacket(
            type = MessageType.ANNOUNCE.value,
            ttl = 10u,
            senderID = unknownPeerID,
            payload = payload
        )
        packet.signature = invalidSignature

        val result = securityManager.validatePacket(packet, unknownPeerID)
        
        assertFalse("ANNOUNCE with invalid signature should be rejected", result)
    }
    
    @Test
    fun `validatePacket - rejects ANNOUNCE packet with malformed payload`() {
        val packet = BitchatPacket(
            type = MessageType.ANNOUNCE.value,
            ttl = 10u,
            senderID = unknownPeerID,
            payload = byteArrayOf(0x00, 0x01, 0x02)
        )
        packet.signature = validSignature

        val result = securityManager.validatePacket(packet, unknownPeerID)
        
        assertFalse("ANNOUNCE with malformed payload should be rejected (cannot extract key)", result)
    }

    @Test
    fun `validatePacket rejects self-signed announce whose Noise key derives another sender ID`() {
        val attackerNoiseKey = ByteArray(32) { 0x6B }
        val announcement = IdentityAnnouncement("Attacker", attackerNoiseKey, otherSigningKey)
        val packet = BitchatPacket(
            type = MessageType.ANNOUNCE.value,
            ttl = 7u,
            senderID = unknownPeerID,
            payload = announcement.encode()!!
        ).also { it.signature = validSignature }

        assertFalse(securityManager.validatePacket(packet, unknownPeerID))
    }

    @Test
    fun `validatePacket rejects announce packet under a different routed sender`() {
        val announcement = IdentityAnnouncement("Peer", otherNoiseKey, otherSigningKey)
        val packet = BitchatPacket(
            type = MessageType.ANNOUNCE.value,
            ttl = 7u,
            senderID = unknownPeerID,
            payload = announcement.encode()!!
        ).also { it.signature = validSignature }

        assertFalse(securityManager.validatePacket(packet, otherPeerID))
    }

    @Test
    fun `validatePacket rejects announce conflicting with persisted authenticated Ed key`() {
        whenever(mockDelegate.getAuthenticatedSigningKey(otherNoiseKey))
            .thenReturn(ByteArray(32) { 0x44 })
        val announcement = IdentityAnnouncement("Copied", otherNoiseKey, otherSigningKey)
        val packet = BitchatPacket(
            type = MessageType.ANNOUNCE.value,
            ttl = 7u,
            senderID = unknownPeerID,
            payload = announcement.encode()!!
        ).also { it.signature = validSignature }

        assertFalse(securityManager.validatePacket(packet, unknownPeerID))
    }

    @Test
    fun `validatePacket - ignores own packets`() {
        val packet = BitchatPacket(
            type = MessageType.MESSAGE.value,
            ttl = 10u,
            senderID = myPeerID,
            payload = dummyPayload
        )
        packet.signature = null

        val result = securityManager.validatePacket(packet, myPeerID)
        
        assertFalse("Own packets should return false (skipped)", result)
    }
    
    @Test
    fun `validatePacket - detects duplicates`() {
        setupKnownPeer(otherPeerID, otherSigningKey)
        
        val packet = BitchatPacket(
            type = MessageType.MESSAGE.value,
            ttl = 10u,
            senderID = otherPeerID,
            payload = dummyPayload
        )
        packet.signature = validSignature

        val result1 = securityManager.validatePacket(packet, otherPeerID)
        assertTrue("First packet should be accepted", result1)

        val result2 = securityManager.validatePacket(packet, otherPeerID)
        assertFalse("Duplicate packet should be rejected", result2)
    }

    @Test
    fun `validatePacket - handles ANNOUNCE duplicates correctly`() {
        val announcement = IdentityAnnouncement(
            nickname = "New User",
            noisePublicKey = otherNoiseKey,
            signingPublicKey = otherSigningKey
        )
        val payload = announcement.encode()!!
        
        // 1. Initial Announce (Fresh)
        val packet1 = BitchatPacket(
            type = MessageType.ANNOUNCE.value,
            ttl = com.bitchat.android.util.AppConstants.MESSAGE_TTL_HOPS, // 7u
            senderID = unknownPeerID,
            payload = payload
        )
        packet1.signature = validSignature
        
        whenever(mockDelegate.getPeerInfo(unknownPeerID)).thenReturn(null)

        assertTrue("First ANNOUNCE should be accepted", securityManager.validatePacket(packet1, unknownPeerID))
        
        // 2. Relayed Duplicate (Lower TTL)
        val packet2 = packet1.copy(ttl = (com.bitchat.android.util.AppConstants.MESSAGE_TTL_HOPS - 1u).toUByte())
        assertFalse("Relayed duplicate ANNOUNCE should be rejected", securityManager.validatePacket(packet2, unknownPeerID))
        
        // 3. Direct Duplicate (Max TTL)
        val packet3 = packet1.copy(ttl = com.bitchat.android.util.AppConstants.MESSAGE_TTL_HOPS)
        assertTrue("Fresh duplicate ANNOUNCE should be accepted", securityManager.validatePacket(packet3, unknownPeerID))
    }

    @Test
    fun `replacement message one sends response without evicting or falsely completing`() = runBlocking {
        val response = byteArrayOf(0x31, 0x32)
        fakeEncryptionService.handshakeResult = NoiseHandshakeProcessingResult(response, false)
        val routed = handshakePacket(byteArrayOf(0x01, 0x02, 0x03))

        val accepted = securityManager.handleNoiseHandshake(routed)

        assertTrue(accepted)
        assertTrue(fakeEncryptionService.removePeerCalls == 0)
        verify(mockDelegate).sendHandshakeResponse(otherPeerID, response)
        verify(mockDelegate, never()).onKeyExchangeCompleted(
            any(), any(), any(), anyOrNull(), anyOrNull()
        )
    }

    @Test
    fun `identity mismatch preserves peer and does not poison retry or completion`() = runBlocking {
        val routed = handshakePacket(byteArrayOf(0x41, 0x42, 0x43))
        fakeEncryptionService.handshakeError = NoiseSessionError.PeerIdentityMismatch(
            otherPeerID,
            "0000000000000000"
        )

        assertFalse(securityManager.handleNoiseHandshake(routed))
        assertTrue(fakeEncryptionService.removePeerCalls == 0)
        verify(mockDelegate, never()).sendHandshakeResponse(any(), any())
        verify(mockDelegate, never()).onKeyExchangeCompleted(
            any(), any(), any(), anyOrNull(), anyOrNull()
        )

        fakeEncryptionService.handshakeError = null
        fakeEncryptionService.handshakeResult = NoiseHandshakeProcessingResult(
            response = null,
            establishedNow = true,
            authenticatedRemoteStaticKey = otherNoiseKey,
            authenticatedSessionToken = sessionToken
        )
        assertTrue("Failed frames must not poison the processed-exchange cache", securityManager.handleNoiseHandshake(routed))
        assertTrue(fakeEncryptionService.handshakeCalls == 2)
        verify(mockDelegate).onKeyExchangeCompleted(
            otherPeerID,
            otherNoiseKey,
            sessionToken,
            "direct-link",
            "direct-link-token"
        )
    }

    @Test
    fun `completion callback fires only for the frame that establishes a bound session`() = runBlocking {
        fakeEncryptionService.handshakeResult = NoiseHandshakeProcessingResult(
            response = null,
            establishedNow = true,
            authenticatedRemoteStaticKey = otherNoiseKey,
            authenticatedSessionToken = sessionToken
        )
        val routed = handshakePacket(byteArrayOf(0x51, 0x52, 0x53))

        assertTrue(securityManager.handleNoiseHandshake(routed))

        verify(mockDelegate, times(1)).onKeyExchangeCompleted(
            otherPeerID,
            otherNoiseKey,
            sessionToken,
            "direct-link",
            "direct-link-token"
        )
        verify(mockDelegate, never()).sendHandshakeResponse(any(), any())
        assertTrue(fakeEncryptionService.removePeerCalls == 0)
    }

    @Test
    fun `relayed completion does not authenticate the relay as the peer link`() = runBlocking {
        fakeEncryptionService.handshakeResult = NoiseHandshakeProcessingResult(
            response = null,
            establishedNow = true,
            authenticatedRemoteStaticKey = otherNoiseKey,
            authenticatedSessionToken = sessionToken
        )
        val routed = handshakePacket(
            payload = byteArrayOf(0x61, 0x62, 0x63),
            ttl = 6u
        )

        assertTrue(securityManager.handleNoiseHandshake(routed))
        verify(mockDelegate).onKeyExchangeCompleted(
            otherPeerID,
            otherNoiseKey,
            sessionToken,
            null,
            null
        )
    }

    @Test
    fun `handshake dedup entries expire by time and allow delayed retry`() = runBlocking {
        val payload = byteArrayOf(0x71, 0x72, 0x73)
        val routed = handshakePacket(payload)

        assertTrue(securityManager.handleNoiseHandshake(routed))
        assertTrue(fakeEncryptionService.handshakeCalls == 1)

        assertFalse("Identical frame inside the dedup window must be dropped",
            securityManager.handleNoiseHandshake(routed))
        assertTrue(fakeEncryptionService.handshakeCalls == 1)

        securityManager.cleanupOldData(
            System.currentTimeMillis() +
                com.bitchat.android.util.AppConstants.Security.KEY_EXCHANGE_DEDUP_TIMEOUT_MS + 1_000
        )

        assertTrue("Expired dedup entries must not block a delayed retry",
            securityManager.handleNoiseHandshake(routed))
        assertTrue(fakeEncryptionService.handshakeCalls == 2)
    }

    private fun setupKnownPeer(peerID: String, signingKey: ByteArray) {
        val info = PeerInfo(
            id = peerID,
            nickname = "Test User",
            isConnected = true,
            isDirectConnection = true,
            noisePublicKey = ByteArray(32),
            signingPublicKey = signingKey,
            isVerifiedNickname = false,
            lastSeen = System.currentTimeMillis()
        )
        whenever(mockDelegate.getPeerInfo(peerID)).thenReturn(info)
    }

    private fun handshakePacket(payload: ByteArray, ttl: UByte = 7u): RoutedPacket {
        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.NOISE_HANDSHAKE.value,
            senderID = otherPeerID.hexToBytes(),
            recipientID = myPeerID.hexToBytes(),
            timestamp = System.currentTimeMillis().toULong(),
            payload = payload,
            ttl = ttl
        )
        return RoutedPacket(
            packet = packet,
            peerID = otherPeerID,
            relayAddress = "direct-link",
            ingressLinkID = "direct-link-token"
        )
    }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
