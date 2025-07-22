package com.bitchat.android.model

import android.util.Log
import org.json.JSONObject
import java.security.MessageDigest
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
    
    companion object {
        private const val TAG = "NoiseIdentityAnnouncement"
        
        /**
         * Parse Noise identity announcement from binary payload
         */
        fun fromBinaryData(payload: ByteArray): NoiseIdentityAnnouncement? {
            return try {
                val jsonString = String(payload, Charsets.UTF_8)
                val json = JSONObject(jsonString)
                
                val peerID = json.getString("peerID")
                val nickname = json.getString("nickname")
                val publicKeyBase64 = json.getString("publicKey")
                val timestampMs = json.getLong("timestamp")
                val signatureBase64 = json.getString("signature")
                val previousPeerID = if (json.has("previousPeerID")) json.getString("previousPeerID") else null
                
                // Decode base64 fields
                val publicKey = android.util.Base64.decode(publicKeyBase64, android.util.Base64.DEFAULT)
                val signature = android.util.Base64.decode(signatureBase64, android.util.Base64.DEFAULT)
                
                // Calculate fingerprint from public key
                val digest = MessageDigest.getInstance("SHA-256")
                val hash = digest.digest(publicKey)
                val fingerprint = hash.joinToString("") { "%02x".format(it) }
                
                NoiseIdentityAnnouncement(
                    peerID = peerID,
                    nickname = nickname,
                    publicKey = publicKey,
                    timestamp = Date(timestampMs),
                    signature = signature,
                    fingerprint = fingerprint,
                    previousPeerID = previousPeerID
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse Noise identity announcement: ${e.message}")
                null
            }
        }
    }
}
