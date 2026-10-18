package com.bitchat.android.services.bridge

import com.bitchat.android.protocol.BitchatPacket
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * Verifies bridge protocol packets against the signing key bound by the
 * sender's authenticated announcement.
 */
internal object BridgePacketSignatureVerifier {
    fun verify(packet: BitchatPacket, publicKey: ByteArray): Boolean {
        val signature = packet.signature?.takeIf { it.size == 64 } ?: return false
        val signingData = packet.toBinaryDataForSigning() ?: return false
        if (publicKey.size != 32) return false
        return runCatching {
            Ed25519Signer().apply {
                init(false, Ed25519PublicKeyParameters(publicKey, 0))
                update(signingData, 0, signingData.size)
            }.verifySignature(signature)
        }.getOrDefault(false)
    }
}

internal object CourierDepositAuthenticator {
    fun authenticate(
        packet: BitchatPacket,
        fromPeerId: String,
        directIngress: Boolean,
        peers: List<VerifiedBridgePeer>,
        isTrusted: (VerifiedBridgePeer) -> Boolean
    ): VerifiedBridgePeer? {
        if (!directIngress || packet.senderID.toHex() != fromPeerId) return null
        val peer = peers.firstOrNull { it.peerId == fromPeerId } ?: return null
        if (!BridgePacketSignatureVerifier.verify(packet, peer.signingKey)) return null
        return peer.takeIf(isTrusted)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
