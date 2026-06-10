package com.bitchat.android.model

import android.os.Parcelable
import com.bitchat.android.protocol.TlvLengthSize
import com.bitchat.android.protocol.TlvReader
import com.bitchat.android.protocol.TlvWriter
import com.bitchat.android.protocol.UnknownTlvPolicy
import com.bitchat.android.util.toHexString
import kotlinx.parcelize.Parcelize

/**
 * Identity announcement structure with TLV encoding
 * Compatible with iOS AnnouncementPacket TLV format
 */
@Parcelize
data class IdentityAnnouncement(
    val nickname: String,
    val noisePublicKey: ByteArray,    // Noise static public key (Curve25519.KeyAgreement)
    val signingPublicKey: ByteArray   // Ed25519 public key for signing
) : Parcelable {

    /**
     * TLV types matching iOS implementation
     */
    private enum class TLVType(val value: UByte) {
        NICKNAME(0x01u),
        NOISE_PUBLIC_KEY(0x02u),
        SIGNING_PUBLIC_KEY(0x03u);  // NEW: Ed25519 signing public key
        
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
        
        return try {
            TlvWriter()
                .put(TLVType.NICKNAME.value.toInt(), nicknameData, TlvLengthSize.ONE_BYTE)
                .put(TLVType.NOISE_PUBLIC_KEY.value.toInt(), noisePublicKey, TlvLengthSize.ONE_BYTE)
                .put(TLVType.SIGNING_PUBLIC_KEY.value.toInt(), signingPublicKey, TlvLengthSize.ONE_BYTE)
                .toByteArray()
        } catch (_: IllegalArgumentException) {
            null
        }
    }
    
    companion object {
        /**
         * Decode from TLV binary data matching iOS implementation
         */
        fun decode(data: ByteArray): IdentityAnnouncement? {
            var nickname: String? = null
            var noisePublicKey: ByteArray? = null
            var signingPublicKey: ByteArray? = null

            val knownTypes = TLVType.values().map { it.value.toInt() }.toSet()
            val fields = TlvReader.decode(
                data = data,
                defaultLengthSize = TlvLengthSize.ONE_BYTE,
                unknownPolicy = UnknownTlvPolicy.SKIP,
                knownTypes = knownTypes
            ) ?: return null

            for (field in fields) {
                val type = TLVType.fromValue(field.type.toUByte()) ?: continue
                when (type) {
                    TLVType.NICKNAME -> {
                        nickname = String(field.value, Charsets.UTF_8)
                    }
                    TLVType.NOISE_PUBLIC_KEY -> {
                        noisePublicKey = field.value
                    }
                    TLVType.SIGNING_PUBLIC_KEY -> {
                        signingPublicKey = field.value
                    }
                }
            }
            
            // All three fields are required
            return if (nickname != null && noisePublicKey != null && signingPublicKey != null) {
                IdentityAnnouncement(nickname, noisePublicKey, signingPublicKey)
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
        
        return true
    }
    
    override fun hashCode(): Int {
        var result = nickname.hashCode()
        result = 31 * result + noisePublicKey.contentHashCode()
        result = 31 * result + signingPublicKey.contentHashCode()
        return result
    }
    
    override fun toString(): String {
        return "IdentityAnnouncement(nickname='$nickname', noisePublicKey=${noisePublicKey.toHexString().take(16)}..., signingPublicKey=${signingPublicKey.toHexString().take(16)}...)"
    }
}
