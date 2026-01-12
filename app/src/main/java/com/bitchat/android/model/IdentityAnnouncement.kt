package com.bitchat.android.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import com.bitchat.android.util.*

/**
 * Identity announcement structure with TLV encoding
 * Compatible with iOS AnnouncementPacket TLV format
 */
@Parcelize
data class IdentityAnnouncement(
    val nickname: String,
    val noisePublicKey: ByteArray,    // Noise static public key (Curve25519.KeyAgreement)
    val signingPublicKey: ByteArray,  // Ed25519 public key for signing
    val features: Int = 0             // Optional feature bitmask (unknown bits ignored by legacy)
) : Parcelable {

    /**
     * TLV types matching iOS implementation
     */
    private enum class TLVType(val value: UByte) {
        NICKNAME(0x01u),
        NOISE_PUBLIC_KEY(0x02u),
        SIGNING_PUBLIC_KEY(0x03u),  // NEW: Ed25519 signing public key
        FEATURES(0x04u);            // Optional feature bitmask (u8 length, up to 4 bytes value)
        
        companion object {
            fun fromValue(value: UByte): TLVType? {
                return values().find { it.value == value }
            }
        }
    }

    /**
     * Encode to TLV binary data matching iOS implementation
     */
    fun encode(): ByteArray? {
        val nicknameData = nickname.toByteArray(Charsets.UTF_8)
        
        // Check size limits
        if (nicknameData.size > 255 || noisePublicKey.size > 255 || signingPublicKey.size > 255) {
            return null
        }
        
        val result = mutableListOf<Byte>()
        
        // TLV for nickname
        result.add(TLVType.NICKNAME.value.toByte())
        result.add(nicknameData.size.toByte())
        result.addAll(nicknameData.toList())
        
        // TLV for noise public key
        result.add(TLVType.NOISE_PUBLIC_KEY.value.toByte())
        result.add(noisePublicKey.size.toByte())
        result.addAll(noisePublicKey.toList())
        
        // TLV for signing public key
        result.add(TLVType.SIGNING_PUBLIC_KEY.value.toByte())
        result.add(signingPublicKey.size.toByte())
        result.addAll(signingPublicKey.toList())
        
        // Optional: features bitmask (encode as minimal big-endian, up to 4 bytes)
        if (features != 0) {
            val featBytes = ByteArray(4)
            featBytes[0] = ((features ushr 24) and 0xFF).toByte()
            featBytes[1] = ((features ushr 16) and 0xFF).toByte()
            featBytes[2] = ((features ushr 8) and 0xFF).toByte()
            featBytes[3] = (features and 0xFF).toByte()
            // Trim leading zeros to minimize length
            val firstNonZero = featBytes.indexOfFirst { it.toInt() != 0 }
            val valueBytes = if (firstNonZero == -1) byteArrayOf(0) else featBytes.copyOfRange(firstNonZero, 4)
            result.add(TLVType.FEATURES.value.toByte())
            result.add(valueBytes.size.toByte())
            result.addAll(valueBytes.toList())
        }
        
        return result.toByteArray()
    }
    
    companion object {
        /**
         * Decode from TLV binary data matching iOS implementation
         */
        fun decode(data: ByteArray): IdentityAnnouncement? {
            // Create defensive copy
            val dataCopy = data.copyOf()
            
            var offset = 0
            var nickname: String? = null
            var noisePublicKey: ByteArray? = null
            var signingPublicKey: ByteArray? = null
            var features: Int = 0
            
            while (offset + 2 <= dataCopy.size) {
                // Read TLV type
                val typeValue = dataCopy[offset].toUByte()
                val type = TLVType.fromValue(typeValue)
                offset += 1
                
                // Read TLV length
                val length = dataCopy[offset].toUByte().toInt()
                offset += 1
                
                // Check bounds
                if (offset + length > dataCopy.size) return null
                
                // Read TLV value
                val value = dataCopy.sliceArray(offset until offset + length)
                offset += length
                
                // Process known TLV types, skip unknown ones for forward compatibility
                when (type) {
                    TLVType.NICKNAME -> {
                        nickname = String(value, Charsets.UTF_8)
                    }
                    TLVType.NOISE_PUBLIC_KEY -> {
                        noisePublicKey = value
                    }
                    TLVType.SIGNING_PUBLIC_KEY -> {
                        signingPublicKey = value
                    }
                    TLVType.FEATURES -> {
                        // Parse big-endian up to 4 bytes
                        var f = 0
                        value.forEach { b ->
                            f = (f shl 8) or (b.toInt() and 0xFF)
                        }
                        features = f
                    }
                    null -> {
                        // Unknown TLV; skip (tolerant decoder for forward compatibility)
                        continue
                    }
                }
            }
            
            // All three fields are required
            return if (nickname != null && noisePublicKey != null && signingPublicKey != null) {
                IdentityAnnouncement(nickname, noisePublicKey, signingPublicKey, features)
            } else {
                null
            }
        }
    }
    
    // Override equals and hashCode since we use ByteArray
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as IdentityAnnouncement
        
        if (nickname != other.nickname) return false
        if (!noisePublicKey.contentEquals(other.noisePublicKey)) return false
        if (!signingPublicKey.contentEquals(other.signingPublicKey)) return false
        if (features != other.features) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        var result = nickname.hashCode()
        result = 31 * result + noisePublicKey.contentHashCode()
        result = 31 * result + signingPublicKey.contentHashCode()
        result = 31 * result + features.hashCode()
        return result
    }
    
    override fun toString(): String {
        return "IdentityAnnouncement(nickname='$nickname', noisePublicKey=${noisePublicKey.joinToString("") { "%02x".format(it) }.take(16)}..., signingPublicKey=${signingPublicKey.joinToString("") { "%02x".format(it) }.take(16)}..., features=$features)"
    }
}
