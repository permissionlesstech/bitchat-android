package com.bitchat.android.model

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Opaque store-and-forward courier envelope compatible with iOS.
 *
 * Version 1 envelopes contain a one-way Noise X ciphertext to the recipient's
 * static key. A non-null [prekeyId] identifies the forward-secret v2 format.
 */
data class CourierEnvelope(
    val recipientTag: ByteArray,
    val expiry: Long,
    val ciphertext: ByteArray,
    val copies: Int = 1,
    val prekeyId: Long? = null
) {
    val normalizedCopies: Int = copies.coerceIn(1, MAX_COPIES)

    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs >= expiry

    fun encode(): ByteArray? {
        if (recipientTag.size != TAG_LENGTH) return null
        if (ciphertext.isEmpty() || ciphertext.size > MAX_CIPHERTEXT_BYTES) return null

        val fields = mutableListOf(
            Tlv16Codec.Field(TLV_RECIPIENT_TAG, recipientTag),
            Tlv16Codec.Field(
                TLV_EXPIRY,
                ByteBuffer.allocate(Long.SIZE_BYTES)
                    .order(ByteOrder.BIG_ENDIAN)
                    .putLong(expiry)
                    .array()
            ),
            Tlv16Codec.Field(TLV_CIPHERTEXT, ciphertext)
        )
        if (normalizedCopies > 1) {
            fields += Tlv16Codec.Field(TLV_COPIES, byteArrayOf(normalizedCopies.toByte()))
        }
        prekeyId?.let {
            if (it !in 0..0xFFFF_FFFFL) return null
            fields += Tlv16Codec.Field(
                TLV_PREKEY_ID,
                ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN).putInt(it.toInt()).array()
            )
        }
        return Tlv16Codec.encode(*fields.toTypedArray())
    }

    companion object {
        const val TAG_LENGTH = 16
        const val MAX_CIPHERTEXT_BYTES = 16 * 1024
        const val MAX_LIFETIME_MS = 24 * 60 * 60 * 1000L
        const val MAX_COPIES = 8

        private const val TLV_RECIPIENT_TAG = 0x01
        private const val TLV_EXPIRY = 0x02
        private const val TLV_CIPHERTEXT = 0x03
        private const val TLV_COPIES = 0x04
        private const val TLV_PREKEY_ID = 0x05
        private val TAG_CONTEXT = "bitchat-courier-tag-v1".toByteArray(Charsets.UTF_8)

        fun decode(data: ByteArray): CourierEnvelope? {
            var recipientTag: ByteArray? = null
            var expiry: Long? = null
            var ciphertext: ByteArray? = null
            var copies = 1
            var prekeyId: Long? = null

            Tlv16Codec.decode(data)?.forEach { field ->
                when (field.type) {
                    TLV_RECIPIENT_TAG -> {
                        if (field.value.size != TAG_LENGTH) return null
                        recipientTag = field.value
                    }
                    TLV_EXPIRY -> {
                        if (field.value.size != Long.SIZE_BYTES) return null
                        expiry = ByteBuffer.wrap(field.value).order(ByteOrder.BIG_ENDIAN).long
                    }
                    TLV_CIPHERTEXT -> {
                        if (field.value.size !in 1..MAX_CIPHERTEXT_BYTES) return null
                        ciphertext = field.value
                    }
                    TLV_COPIES -> {
                        if (field.value.size != 1) return null
                        copies = field.value[0].toInt() and 0xFF
                    }
                    TLV_PREKEY_ID -> {
                        if (field.value.size != Int.SIZE_BYTES) return null
                        prekeyId =
                            ByteBuffer.wrap(field.value)
                                .order(ByteOrder.BIG_ENDIAN)
                                .int.toLong() and 0xFFFF_FFFFL
                    }
                }
            } ?: return null

            return CourierEnvelope(
                recipientTag = recipientTag ?: return null,
                expiry = expiry ?: return null,
                ciphertext = ciphertext ?: return null,
                copies = copies,
                prekeyId = prekeyId
            )
        }

        fun epochDay(nowMs: Long = System.currentTimeMillis()): Long =
            (nowMs.coerceAtLeast(0L) / 86_400_000L) and 0xFFFF_FFFFL

        fun recipientTag(noiseStaticKey: ByteArray, epochDay: Long): ByteArray {
            require(epochDay in 0..0xFFFF_FFFFL)
            val message = TAG_CONTEXT + ByteBuffer.allocate(Int.SIZE_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(epochDay.toInt())
                .array()
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(noiseStaticKey, "HmacSHA256"))
            return mac.doFinal(message).copyOf(TAG_LENGTH)
        }

        fun candidateTags(noiseStaticKey: ByteArray, aroundMs: Long = System.currentTimeMillis()): List<ByteArray> {
            val day = epochDay(aroundMs)
            return listOf(if (day == 0L) 0L else day - 1, day, (day + 1) and 0xFFFF_FFFFL)
                .map { recipientTag(noiseStaticKey, it) }
        }

    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is CourierEnvelope &&
                recipientTag.contentEquals(other.recipientTag) &&
                expiry == other.expiry &&
                ciphertext.contentEquals(other.ciphertext) &&
                normalizedCopies == other.normalizedCopies &&
                prekeyId == other.prekeyId)

    override fun hashCode(): Int {
        var result = recipientTag.contentHashCode()
        result = 31 * result + expiry.hashCode()
        result = 31 * result + ciphertext.contentHashCode()
        result = 31 * result + normalizedCopies
        result = 31 * result + (prekeyId?.hashCode() ?: 0)
        return result
    }
}
