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
    val publicKey: ByteArray  // FIXED: Made non-nullable to match iOS and AnnouncementPacket
) : Parcelable {

    /**
     * TLV types matching iOS implementation
     */
    private enum class TLVType(val value: UByte) {
        NICKNAME(0x01u),
        NOISE_PUBLIC_KEY(0x02u);
        
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
        if (nicknameData.size > 255 || publicKey.size > 255) {
            return null
        }
        
        val result = mutableListOf<Byte>()
        
        // TLV for nickname
        result.add(TLVType.NICKNAME.value.toByte())
        result.add(nicknameData.size.toByte())
        result.addAll(nicknameData.toList())
        
        // TLV for public key
        result.add(TLVType.NOISE_PUBLIC_KEY.value.toByte())
        result.add(publicKey.size.toByte())
        result.addAll(publicKey.toList())
        
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
            var publicKey: ByteArray? = null
            
            while (offset + 2 <= dataCopy.size) {
                // Read TLV type
                val typeValue = dataCopy[offset].toUByte()
                val type = TLVType.fromValue(typeValue) ?: return null
                offset += 1
                
                // Read TLV length
                val length = dataCopy[offset].toUByte().toInt()
                offset += 1
                
                // Check bounds
                if (offset + length > dataCopy.size) return null
                
                // Read TLV value
                val value = dataCopy.sliceArray(offset until offset + length)
                offset += length
                
                when (type) {
                    TLVType.NICKNAME -> {
                        nickname = String(value, Charsets.UTF_8)
                    }
                    TLVType.NOISE_PUBLIC_KEY -> {
                        publicKey = value
                    }
                }
            }
            
            // Both fields are required
            return if (nickname != null && publicKey != null) {
                IdentityAnnouncement(nickname, publicKey)
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
        if (!publicKey.contentEquals(other.publicKey)) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        var result = nickname.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        return result
    }
    
    override fun toString(): String {
        return "IdentityAnnouncement(nickname='$nickname', publicKey=${publicKey.joinToString("") { "%02x".format(it) }.take(16)}...)"
    }
}
