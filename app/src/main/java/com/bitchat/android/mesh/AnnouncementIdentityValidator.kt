package com.bitchat.android.mesh

import com.bitchat.android.model.IdentityAnnouncement
import com.bitchat.android.noise.NoisePeerIdentity
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.util.toHexString

/** Canonical, side-effect-free preflight for a self-signed mesh announcement. */
object AnnouncementIdentityValidator {
    private const val MAX_CLOCK_SKEW_MS = 10 * 60 * 1_000L

    fun verify(
        packet: BitchatPacket,
        claimedPeerID: String,
        nowMs: Long = System.currentTimeMillis(),
        verifyEd25519: (signature: ByteArray, data: ByteArray, publicKey: ByteArray) -> Boolean
    ): IdentityAnnouncement? {
        if (packet.type != MessageType.ANNOUNCE.value) return null
        val now = nowMs.coerceAtLeast(0).toULong()
        val skew = if (packet.timestamp >= now) packet.timestamp - now else now - packet.timestamp
        if (skew > MAX_CLOCK_SKEW_MS.toULong()) return null
        val announcement = IdentityAnnouncement.decode(packet.payload) ?: return null
        if (announcement.signingPublicKey.size != 32) return null

        val derivedPeerID = NoisePeerIdentity.derivePeerID(announcement.noisePublicKey) ?: return null
        if (packet.senderID.toHexString() != derivedPeerID || claimedPeerID != derivedPeerID) return null

        val signature = packet.signature ?: return null
        val canonicalData = packet.toBinaryDataForSigning() ?: return null
        return if (verifyEd25519(signature, canonicalData, announcement.signingPublicKey)) {
            announcement
        } else {
            null
        }
    }
}
