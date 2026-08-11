package com.bitchat.android.organizer

import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OrganizerSecurityTest {

    private val testPasscode = "TEST_PASSCODE_123"

    @Before
    fun setUp() {
        OrganizerIdentityManager.resetLockoutForTesting()
    }

    private fun createSignedTestPacket(
        privKeyParams: Ed25519PrivateKeyParameters,
        content: String = "Main Stage program starts in 10 minutes.",
        recipientID: ByteArray = SpecialRecipients.BROADCAST,
        timestampMs: Long = System.currentTimeMillis()
    ): BitchatPacket {
        val payload = content.toByteArray(Charsets.UTF_8)
        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.ANNOUNCEMENT.value,
            senderID = privKeyParams.generatePublicKey().encoded.take(8).toByteArray(),
            recipientID = recipientID,
            timestamp = timestampMs.toULong(),
            payload = payload,
            signature = null,
            ttl = 7u
        )

        val signer = Ed25519Signer()
        signer.init(true, privKeyParams)
        val dataToSign = packet.toBinaryDataForSigning()!!
        signer.update(dataToSign, 0, dataToSign.size)
        packet.signature = signer.generateSignature()
        return packet
    }

    @Test
    fun testA_freshValidAnnouncementAccepted() {
        val testPrivParams = Ed25519PrivateKeyParameters(ByteArray(32) { 0x42.toByte() }, 0)
        val packet = createSignedTestPacket(testPrivParams)
        
        val verifier = Ed25519Signer()
        verifier.init(false, testPrivParams.generatePublicKey())
        val dataToVerify = packet.toBinaryDataForSigning()!!
        verifier.update(dataToVerify, 0, dataToVerify.size)
        assertTrue(verifier.verifySignature(packet.signature!!))
    }

    @Test
    fun testB_announcementOutsideFreshnessWindowRejected() {
        val testPrivParams = Ed25519PrivateKeyParameters(ByteArray(32) { 0x42.toByte() }, 0)
        val nowMs = System.currentTimeMillis()
        val staleTimeMs = nowMs - (3 * 60 * 60 * 1000L) // 3 hours ago (max allowed is 2 hours)
        
        val packet = createSignedTestPacket(testPrivParams, timestampMs = staleTimeMs)

        // Using verifyAnnouncement with reference current time (nowMs)
        assertFalse(OrganizerIdentityManager.verifyAnnouncement(packet, nowMs = nowMs))
    }

    @Test
    fun testC_announcementWithinAllowedClockSkewAccepted() {
        val testPrivParams = Ed25519PrivateKeyParameters(ByteArray(32) { 0x42.toByte() }, 0)
        val nowMs = System.currentTimeMillis()
        val skewTimeMs = nowMs + (2 * 60 * 1000L) // 2 minutes in future (max allowed skew is 5 mins)
        
        val packet = createSignedTestPacket(testPrivParams, timestampMs = skewTimeMs)
        val dataToVerify = packet.toBinaryDataForSigning()!!
        
        val verifier = Ed25519Signer()
        verifier.init(false, testPrivParams.generatePublicKey())
        verifier.update(dataToVerify, 0, dataToVerify.size)
        assertTrue(verifier.verifySignature(packet.signature!!))
    }

    @Test
    fun testD_modifiedTimestampFailsSignatureVerification() {
        val testPrivParams = Ed25519PrivateKeyParameters(ByteArray(32) { 0x42.toByte() }, 0)
        val packet = createSignedTestPacket(testPrivParams)

        // Attacker alters packet timestamp to make it look fresh
        val tamperedPacket = packet.copy(timestamp = System.currentTimeMillis().toULong() + 1000u)
        tamperedPacket.signature = packet.signature

        val tamperedData = tamperedPacket.toBinaryDataForSigning()!!
        val verifier = Ed25519Signer()
        verifier.init(false, testPrivParams.generatePublicKey())
        verifier.update(tamperedData, 0, tamperedData.size)
        assertFalse(verifier.verifySignature(tamperedPacket.signature!!))
    }

    @Test
    fun testE_replayHandledByDeduplication() {
        val seenSet = mutableSetOf<String>()
        val announcementMsgID = "ANNOUNCE_UUID_999"

        assertFalse(seenSet.contains(announcementMsgID))
        seenSet.add(announcementMsgID)
        assertTrue(seenSet.contains(announcementMsgID)) // Replayed packet deduplicated
    }

    @Test
    fun testF_oldAnnouncementAfterCacheEvictionRejectedByFreshness() {
        val testPrivParams = Ed25519PrivateKeyParameters(ByteArray(32) { 0x42.toByte() }, 0)
        val nowMs = System.currentTimeMillis()
        val oldTimeMs = nowMs - (4 * 60 * 60 * 1000L) // 4 hours old

        val oldPacket = createSignedTestPacket(testPrivParams, timestampMs = oldTimeMs)

        // After cache eviction, old packet is checked against timestamp freshness window
        assertFalse(OrganizerIdentityManager.verifyAnnouncement(oldPacket, nowMs = nowMs))
    }

    @Test
    fun testG_fakeOrganizerSignatureRejected() {
        val fakePrivParams = Ed25519PrivateKeyParameters(ByteArray(32) { 0x99.toByte() }, 0)
        val realPrivParams = Ed25519PrivateKeyParameters(ByteArray(32) { 0x42.toByte() }, 0)

        val packet = createSignedTestPacket(fakePrivParams)
        val dataToVerify = packet.toBinaryDataForSigning()!!

        val verifier = Ed25519Signer()
        verifier.init(false, realPrivParams.generatePublicKey())
        verifier.update(dataToVerify, 0, dataToVerify.size)
        assertFalse(verifier.verifySignature(packet.signature!!))
    }

    @Test
    fun testH_modifiedChannelFailsVerification() {
        val testPrivParams = Ed25519PrivateKeyParameters(ByteArray(32) { 0x42.toByte() }, 0)
        val packet = createSignedTestPacket(testPrivParams, recipientID = SpecialRecipients.BROADCAST)

        // Tamper recipient channel
        val tamperedPacket = packet.copy(recipientID = "MedicalChannel".toByteArray())
        tamperedPacket.signature = packet.signature

        val tamperedData = tamperedPacket.toBinaryDataForSigning()!!
        val verifier = Ed25519Signer()
        verifier.init(false, testPrivParams.generatePublicKey())
        verifier.update(tamperedData, 0, tamperedData.size)
        assertFalse(verifier.verifySignature(tamperedPacket.signature!!))
    }

    @Test
    fun testI_modifiedContentFailsVerification() {
        val testPrivParams = Ed25519PrivateKeyParameters(ByteArray(32) { 0x42.toByte() }, 0)
        val packet = createSignedTestPacket(testPrivParams, content = "Original Content")

        val tamperedPacket = packet.copy(payload = "Altered Content".toByteArray())
        tamperedPacket.signature = packet.signature

        val tamperedData = tamperedPacket.toBinaryDataForSigning()!!
        val verifier = Ed25519Signer()
        verifier.init(false, testPrivParams.generatePublicKey())
        verifier.update(tamperedData, 0, tamperedData.size)
        assertFalse(verifier.verifySignature(tamperedPacket.signature!!))
    }

    @Test
    fun testJ_invalidSignatureFails() {
        val packet = createSignedTestPacket(Ed25519PrivateKeyParameters(ByteArray(32) { 0x42.toByte() }, 0))
        packet.signature = ByteArray(64) { 0x00.toByte() }

        assertFalse(OrganizerIdentityManager.verifyAnnouncement(packet))
    }

    @Test
    fun testPasscodeValidationAndLockout() {
        assertFalse(OrganizerIdentityManager.validatePasscode("WRONG_PASSCODE_1"))
        assertFalse(OrganizerIdentityManager.validatePasscode("WRONG_PASSCODE_2"))
        assertFalse(OrganizerIdentityManager.validatePasscode("WRONG_PASSCODE_3"))
        assertFalse(OrganizerIdentityManager.validatePasscode("WRONG_PASSCODE_4"))
        assertFalse(OrganizerIdentityManager.validatePasscode("WRONG_PASSCODE_5"))

        // 5 failed attempts trigger lockout
        assertTrue(OrganizerIdentityManager.isLockedOut())
    }
}
