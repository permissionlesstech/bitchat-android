package com.bitchat.android.model

import java.util.*

/**
 * Noise Identity Announcement data class (compatible with iOS version)
 */
data class NoiseIdentityAnnouncement(
    val peerID: String,
    val nickname: String,
    val publicKey: ByteArray,
    val timestamp: Date,
    val signature: ByteArray,
    val fingerprint: String?,
    val previousPeerID: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as NoiseIdentityAnnouncement

        if (peerID != other.peerID) return false
        if (nickname != other.nickname) return false
        if (!publicKey.contentEquals(other.publicKey)) return false
        if (timestamp != other.timestamp) return false
        if (!signature.contentEquals(other.signature)) return false
        if (fingerprint != other.fingerprint) return false
        if (previousPeerID != other.previousPeerID) return false

        return true
    }

    override fun hashCode(): Int {
        var result = peerID.hashCode()
        result = 31 * result + nickname.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + signature.contentHashCode()
        result = 31 * result + (fingerprint?.hashCode() ?: 0)
        result = 31 * result + (previousPeerID?.hashCode() ?: 0)
        return result
    }
}
