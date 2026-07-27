package com.bitchat.android.services.bridge

import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CourierDepositAuthenticatorTest {
    private val privateKey = Ed25519PrivateKeyParameters(ByteArray(32) { (it + 1).toByte() }, 0)
    private val peerId = "0011223344556677"
    private val peer = VerifiedBridgePeer(
        peerId = peerId,
        nickname = "peer",
        noiseKey = ByteArray(32) { 0x22 },
        signingKey = privateKey.generatePublicKey().encoded,
        isVerifiedNickname = true,
        capabilities = null,
        bridgeCell = "u4pruy",
        lastSeenMs = 1
    )

    @Test
    fun `direct trusted announce-bound depositor is accepted`() {
        assertNotNull(authenticate(signedPacket()))
    }

    @Test
    fun `relayed or claimed-sender mismatch is rejected`() {
        assertNull(authenticate(signedPacket(), directIngress = false))
        assertNull(authenticate(signedPacket(), fromPeerId = "8899aabbccddeeff"))
    }

    @Test
    fun `untrusted or tampered depositor is rejected`() {
        assertNull(authenticate(signedPacket(), trusted = false))
        assertNull(authenticate(signedPacket().copy(payload = "tampered".toByteArray())))
    }

    @Test
    fun `ttl relay mutation does not invalidate canonical iOS-compatible signature`() {
        assertNotNull(authenticate(signedPacket().copy(ttl = 6u)))
    }

    private fun authenticate(
        packet: BitchatPacket,
        fromPeerId: String = peerId,
        directIngress: Boolean = true,
        trusted: Boolean = true
    ): VerifiedBridgePeer? = CourierDepositAuthenticator.authenticate(
        packet = packet,
        fromPeerId = fromPeerId,
        directIngress = directIngress,
        peers = listOf(peer),
        isTrusted = { trusted }
    )

    private fun signedPacket(): BitchatPacket {
        val unsigned = BitchatPacket(
            type = MessageType.COURIER_ENVELOPE.value,
            senderID = peerId.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
            recipientID = ByteArray(8) { 0x44 },
            timestamp = 1_750_000_000_000u,
            payload = "courier-envelope".toByteArray(),
            ttl = 7u
        )
        val data = requireNotNull(unsigned.toBinaryDataForSigning())
        val signature = Ed25519Signer().apply {
            init(true, privateKey)
            update(data, 0, data.size)
        }.generateSignature()
        return unsigned.copy(signature = signature)
    }
}
