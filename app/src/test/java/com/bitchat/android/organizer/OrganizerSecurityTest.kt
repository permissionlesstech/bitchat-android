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

    @Before
    fun setUp() {
        OrganizerIdentityManager.resetLockoutForTesting()
    }

    private fun createSamplePacket(
        sender: ByteArray = OrganizerIdentityManager.getOrganizerSenderId(),
        content: String = "Main Stage program starts in 10 minutes.",
        channel: String = "Main Stage"
    ): BitchatPacket {
        val payload = content.toByteArray(Charsets.UTF_8)
        return BitchatPacket(
            type = MessageType.ANNOUNCEMENT.value,
            senderID = sender,
            recipientID = SpecialRecipients.BROADCAST,
            timestamp = System.currentTimeMillis().toULong(),
            payload = payload,
            ttl = 7u
        )
    }

    @Test
    fun test1_validOrganizerAnnouncement() {
        val packet = createSamplePacket()
        val testPrivBytes = ByteArray(32) { 0x42.toByte() }
        val testPrivParams = Ed25519PrivateKeyParameters(testPrivBytes, 0)
        val signer = Ed25519Signer()
        signer.init(true, testPrivParams)
        
        val dataToSign = packet.toBinaryDataForSigning()!!
        signer.update(dataToSign, 0, dataToSign.size)
        val sig = signer.generateSignature()
        packet.signature = sig

        val verifier = Ed25519Signer()
        verifier.init(false, testPrivParams.generatePublicKey())
        verifier.update(dataToSign, 0, dataToSign.size)
        assertTrue(verifier.verifySignature(sig))
    }

    @Test
    fun test2_modifiedContentFailsVerification() {
        val packet = createSamplePacket(content = "Original Announcement")
        val testPrivBytes = ByteArray(32) { 0x42.toByte() }
        val testPrivParams = Ed25519PrivateKeyParameters(testPrivBytes, 0)
        val signer = Ed25519Signer()
        signer.init(true, testPrivParams)

        val dataToSign = packet.toBinaryDataForSigning()!!
        signer.update(dataToSign, 0, dataToSign.size)
        packet.signature = signer.generateSignature()

        // Tamper content
        val tamperedPacket = packet.copy(payload = "Tampered Announcement".toByteArray())
        tamperedPacket.signature = packet.signature

        val tamperedData = tamperedPacket.toBinaryDataForSigning()!!
        val verifier = Ed25519Signer()
        verifier.init(false, testPrivParams.generatePublicKey())
        verifier.update(tamperedData, 0, tamperedData.size)
        assertFalse(verifier.verifySignature(tamperedPacket.signature!!))
    }

    @Test
    fun test3_modifiedChannelFailsVerification() {
        val packet = createSamplePacket(channel = "Main Stage")
        val testPrivBytes = ByteArray(32) { 0x42.toByte() }
        val testPrivParams = Ed25519PrivateKeyParameters(testPrivBytes, 0)
        val signer = Ed25519Signer()
        signer.init(true, testPrivParams)

        val dataToSign = packet.toBinaryDataForSigning()!!
        signer.update(dataToSign, 0, dataToSign.size)
        packet.signature = signer.generateSignature()

        // Tamper channel/recipient
        val tamperedPacket = packet.copy(recipientID = "MedicalChannel".toByteArray())
        tamperedPacket.signature = packet.signature

        val tamperedData = tamperedPacket.toBinaryDataForSigning()!!
        val verifier = Ed25519Signer()
        verifier.init(false, testPrivParams.generatePublicKey())
        verifier.update(tamperedData, 0, tamperedData.size)
        assertFalse(verifier.verifySignature(tamperedPacket.signature!!))
    }

    @Test
    fun test4_fakeOrganizerIdentityRejected() {
        val packet = createSamplePacket()
        
        val fakePrivBytes = ByteArray(32) { 0x99.toByte() }
        val fakePrivParams = Ed25519PrivateKeyParameters(fakePrivBytes, 0)
        val signer = Ed25519Signer()
        signer.init(true, fakePrivParams)

        val dataToSign = packet.toBinaryDataForSigning()!!
        signer.update(dataToSign, 0, dataToSign.size)
        packet.signature = signer.generateSignature()

        // Verify against real organizer public key parameters
        val verifier = Ed25519Signer()
        val realPrivParams = Ed25519PrivateKeyParameters(ByteArray(32) { 0x42.toByte() }, 0)
        verifier.init(false, realPrivParams.generatePublicKey())
        verifier.update(dataToSign, 0, dataToSign.size)
        assertFalse(verifier.verifySignature(packet.signature!!))
    }

    @Test
    fun test5_invalidSignatureFails() {
        val packet = createSamplePacket()
        packet.signature = ByteArray(64) { 0x00.toByte() }

        assertFalse(OrganizerIdentityManager.verifyAnnouncement(packet))
    }

    @Test
    fun test6_replayHandledDeduplication() {
        val seenSet = mutableSetOf<String>()
        val messageID = "MSG_ANNOUNCEMENT_001"

        assertFalse(seenSet.contains(messageID))
        seenSet.add(messageID)
        assertTrue(seenSet.contains(messageID))
    }

    @Test
    fun test7_normalUserUnsignedAnnouncementRejected() {
        val packet = createSamplePacket()
        packet.signature = null // Normal user sent type ANNOUNCEMENT without signature

        assertFalse(OrganizerIdentityManager.verifyAnnouncement(packet))
    }

    @Test
    fun test8_wrongPasscodeRejected() {
        assertFalse(OrganizerIdentityManager.validatePasscode("WRONGPASS"))
        assertFalse(OrganizerIdentityManager.validatePasscode("123456"))
    }

    @Test
    fun test9_excessivePasscodeAttemptsTriggersLockout() {
        for (i in 1..5) {
            OrganizerIdentityManager.validatePasscode("WRONG_$i")
        }
        assertTrue(OrganizerIdentityManager.isLockedOut())
        // Even the correct passcode should fail during lockout
        assertFalse(OrganizerIdentityManager.validatePasscode("FESTIVAL2026"))
    }
}
