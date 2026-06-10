package com.bitchat.android.mesh

import android.os.Build
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.model.IdentityAnnouncement
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
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
    private val unknownPeerID = "9999888877776666"

    private val dummyPayload = "Hello World".toByteArray()
    private val validSignature = ByteArray(64) { 1 }
    private val invalidSignature = ByteArray(64) { 0 }
    
    // Key pairs (using dummy bytes for mock verification)
    private val otherSigningKey = ByteArray(32) { 0xA }
    private val otherNoiseKey = ByteArray(32) { 0xB }

    // Fake implementation to bypass initialization issues in tests
    open class FakeEncryptionService : EncryptionService(RuntimeEnvironment.getApplication()) {
        var shouldVerify: Boolean = true
        var lastVerifySignature: ByteArray? = null
        var lastVerifyKey: ByteArray? = null

        override fun initialize() {
            // Do nothing to avoid KeyStore access in tests
        }

        override fun verifyEd25519Signature(signature: ByteArray, data: ByteArray, publicKeyBytes: ByteArray): Boolean {
            lastVerifySignature = signature
            lastVerifyKey = publicKeyBytes
            
            // Simple logic: if configured to verify, check if signature matches validSignature
            // We use the signature bytes passed in setup()
            if (shouldVerify) {
                 return signature.contentEquals(byteArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1))
            }
            return false
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
}
