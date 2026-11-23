package com.bitchat.android.mesh

import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.model.IdentityAnnouncement
import com.bitchat.android.noise.NoisePeerIdentity
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.util.AppConstants
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class SecurityManagerAnnounceReplayTest {
    private val noiseKey = ByteArray(32) { 0xB }
    private val signingKey = ByteArray(32) { 0xA }
    private val derivedPeerID = NoisePeerIdentity.derivePeerID(noiseKey)!!
    private val senderIdBytes = derivedPeerID.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val validPayload = IdentityAnnouncement(
        nickname = "alice",
        noisePublicKey = noiseKey,
        signingPublicKey = signingKey
    ).encode()!!

    private fun newManager(): SecurityManager {
        val encryptionService = mock<EncryptionService> {
            on { verifyEd25519Signature(any(), any(), any()) } doReturn true
        }
        return SecurityManager(encryptionService, "1111111111111111")
    }

    private fun announcePacket(timestampMs: Long): BitchatPacket = BitchatPacket(
        type = MessageType.ANNOUNCE.value,
        senderID = senderIdBytes,
        timestamp = timestampMs.toULong(),
        payload = validPayload,
        signature = ByteArray(64) { 0x01 },
        ttl = AppConstants.MESSAGE_TTL_HOPS
    )

    @Test
    fun `in-window signed announce is accepted`() {
        val manager = newManager()
        assertTrue(manager.validatePacket(announcePacket(System.currentTimeMillis()), derivedPeerID))
    }

    @Test
    fun `stale announce beyond 5-minute replay window is rejected`() {
        val manager = newManager()
        val staleTimestamp = System.currentTimeMillis() - AppConstants.Security.MESSAGE_TIMEOUT_MS - 60_000L
        assertFalse(manager.validatePacket(announcePacket(staleTimestamp), derivedPeerID))
    }

    @Test
    fun `future-dated announce beyond 5-minute replay window is rejected`() {
        val manager = newManager()
        val futureTimestamp = System.currentTimeMillis() + AppConstants.Security.MESSAGE_TIMEOUT_MS + 60_000L
        assertFalse(manager.validatePacket(announcePacket(futureTimestamp), derivedPeerID))
    }
}
