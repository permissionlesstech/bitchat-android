package com.bitchat.android.model

import android.util.Log
import com.bitchat.android.util.*
import java.security.MessageDigest
import java.util.*

/**
 * Noise Identity Announcement data class (compatible with iOS version)
 * Enhanced identity announcement with rotation support and binary protocol
 */
data class NoiseIdentityAnnouncement(
    val peerID: String,               // Current ephemeral peer ID
    val publicKey: ByteArray,         // Noise static public key
    val signingPublicKey: ByteArray,  // Ed25519 signing public key
    val nickname: String,             // Current nickname
    val timestamp: Date,              // When this binding was created
    val previousPeerID: String?,      // Previous peer ID (for smooth transition)
    val signature: ByteArray         // Signature proving ownership
) {
    
    // Computed fingerprint from public key
    val fingerprint: String by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(publicKey)
        hash.joinToString("") { "%02x".format(it) }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as NoiseIdentityAnnouncement

        if (peerID != other.peerID) return false
        if (nickname != other.nickname) return false
        if (!publicKey.contentEquals(other.publicKey)) return false
        if (!signingPublicKey.contentEquals(other.signingPublicKey)) return false
        if (timestamp != other.timestamp) return false
        if (!signature.contentEquals(other.signature)) return false
        if (previousPeerID != other.previousPeerID) return false

        return true
    }

    override fun hashCode(): Int {
        var result = peerID.hashCode()
        result = 31 * result + nickname.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + signingPublicKey.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + signature.contentHashCode()
        result = 31 * result + (previousPeerID?.hashCode() ?: 0)
        return result
    }
    
    // MARK: - Binary Encoding
    
    fun toBinaryData(): ByteArray {
        val builder = BinaryDataBuilder()
        
        // Flags byte: bit 0 = hasPreviousPeerID
        var flags: UByte = 0u
        if (previousPeerID != null) flags = flags or 0x01u
        builder.appendUInt8(flags)
        
        // PeerID as 8-byte hex string
        val peerData = hexStringToByteArray(peerID)
        // Directly append the 8 bytes without length prefix since this is a fixed field
        for (byte in peerData) {
            builder.buffer.add(byte)
        }
        
        builder.appendData(publicKey)
        builder.appendData(signingPublicKey)
        builder.appendString(nickname)
        builder.appendDate(timestamp)
        
        if (previousPeerID != null) {
            // Previous PeerID as 8-byte hex string
            val prevData = hexStringToByteArray(previousPeerID)
            // Directly append the 8 bytes without length prefix since this is a fixed field
            for (byte in prevData) {
                builder.buffer.add(byte)
            }
        }
        
        builder.appendData(signature)
        
        return builder.toByteArray()
    }
    
    companion object {
        private const val TAG = "NoiseIdentityAnnouncement"
        
        /**
         * Parse Noise identity announcement from binary payload with proper iOS compatibility
         */
        fun fromBinaryData(data: ByteArray): NoiseIdentityAnnouncement? {
            return try {
                // Create defensive copy
                val dataCopy = data.copyOf()
                
                // Minimum size check: flags(1) + peerID(8) + min data lengths
                if (dataCopy.size < 20) {
                    Log.w(TAG, "Data too small for NoiseIdentityAnnouncement: ${dataCopy.size} bytes")
                    return null
                }
                
                val offsetArray = intArrayOf(0)
                
                val flags = dataCopy.readUInt8(offsetArray) ?: run {
                    Log.w(TAG, "Failed to read flags")
                    return null
                }
                val hasPreviousPeerID = (flags.toInt() and 0x01) != 0
                
                // Read peerID using safe method
                val peerIDBytes = dataCopy.readFixedBytes(offsetArray, 8) ?: run {
                    Log.w(TAG, "Failed to read peerID bytes")
                    return null
                }
                val peerID = peerIDBytes.hexEncodedString()
                
                val publicKey = dataCopy.readData(offsetArray) ?: run {
                    Log.w(TAG, "Failed to read public key")
                    return null
                }
                
                val signingPublicKey = dataCopy.readData(offsetArray) ?: run {
                    Log.w(TAG, "Failed to read signing public key")
                    return null
                }
                
                val nickname = dataCopy.readString(offsetArray) ?: run {
                    Log.w(TAG, "Failed to read nickname")
                    return null
                }
                
                val timestamp = dataCopy.readDate(offsetArray) ?: run {
                    Log.w(TAG, "Failed to read timestamp")
                    return null
                }
                
                var previousPeerID: String? = null
                if (hasPreviousPeerID) {
                    // Read previousPeerID using safe method
                    val prevIDBytes = dataCopy.readFixedBytes(offsetArray, 8) ?: run {
                        Log.w(TAG, "Failed to read previousPeerID bytes")
                        return null
                    }
                    previousPeerID = prevIDBytes.hexEncodedString()
                }
                
                val signature = dataCopy.readData(offsetArray) ?: run {
                    Log.w(TAG, "Failed to read signature")
                    return null
                }
                
                Log.d(TAG, "Successfully parsed NoiseIdentityAnnouncement: peerID=$peerID, nickname=$nickname")
                
                return NoiseIdentityAnnouncement(
                    peerID = peerID,
                    publicKey = publicKey,
                    signingPublicKey = signingPublicKey,
                    nickname = nickname,
                    timestamp = timestamp,
                    previousPeerID = previousPeerID,
                    signature = signature
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse Noise identity announcement: ${e.message}", e)
                null
            }
        }
        
        /**
         * Convert hex string peer ID to binary data (8 bytes) - exactly same as iOS
         */
        private fun hexStringToByteArray(hexString: String): ByteArray {
            val result = ByteArray(8) { 0 } // Initialize with zeros, exactly 8 bytes
            var tempID = hexString
            var index = 0
            
            while (tempID.length >= 2 && index < 8) {
                val hexByte = tempID.substring(0, 2)
                val byte = hexByte.toIntOrNull(16)?.toByte()
                if (byte != null) {
                    result[index] = byte
                }
                tempID = tempID.substring(2)
                index++
            }
            
            return result
        }
    }
}
