package com.bitchat.android.model

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Opaque store-and-forward envelope, wire-compatible with iOS CourierEnvelope. */
data class CourierEnvelope(
    val recipientTag: ByteArray,
    val expiry: ULong,
    val ciphertext: ByteArray,
    val copies: UByte = 1u
) {
    companion object {
        const val TAG_LENGTH = 16
        const val MAX_CIPHERTEXT_BYTES = 16 * 1024
        const val MAX_LIFETIME_MS = 24 * 60 * 60 * 1000L
        const val MAX_COPIES = 8
        private val TAG_DOMAIN = "bitchat-courier-tag-v1".toByteArray(Charsets.UTF_8)

        fun epochDay(nowMs: Long): UInt = Math.floorDiv(nowMs, 86_400_000L).toUInt()

        fun recipientTag(noiseStaticKey: ByteArray, epochDay: UInt): ByteArray {
            require(noiseStaticKey.size == 32)
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(noiseStaticKey, "HmacSHA256"))
            mac.update(TAG_DOMAIN)
            mac.update(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(epochDay.toInt()).array())
            return mac.doFinal().copyOf(TAG_LENGTH)
        }

        fun decode(data: ByteArray): CourierEnvelope? {
            var offset = 0
            var tag: ByteArray? = null
            var expiry: ULong? = null
            var ciphertext: ByteArray? = null
            var copies: UByte = 1u
            while (offset + 3 <= data.size) {
                val type = data[offset].toUByte()
                val length = ((data[offset + 1].toInt() and 0xff) shl 8) or
                    (data[offset + 2].toInt() and 0xff)
                offset += 3
                if (offset + length > data.size) return null
                val value = data.copyOfRange(offset, offset + length)
                offset += length
                when (type.toInt()) {
                    1 -> if (length == TAG_LENGTH && tag == null) tag = value else return null
                    2 -> if (length == 8 && expiry == null) expiry = ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN).long.toULong() else return null
                    3 -> if (ciphertext == null) ciphertext = value else return null
                    4 -> {
                        if (length != 1 || copies != 1u.toUByte()) return null
                        copies = value[0].toUByte()
                        if (copies !in 2u.toUByte()..MAX_COPIES.toUByte()) return null
                    }
                }
            }
            if (offset != data.size) return null
            val requiredTag = tag ?: return null
            val requiredExpiry = expiry ?: return null
            val requiredCiphertext = ciphertext ?: return null
            if (requiredCiphertext.isEmpty() || requiredCiphertext.size > MAX_CIPHERTEXT_BYTES) return null
            return CourierEnvelope(requiredTag, requiredExpiry, requiredCiphertext, copies)
        }
    }

    fun encode(): ByteArray? {
        if (recipientTag.size != TAG_LENGTH || ciphertext.isEmpty() || ciphertext.size > MAX_CIPHERTEXT_BYTES ||
            copies !in 1u.toUByte()..MAX_COPIES.toUByte()
        ) return null
        val fields = mutableListOf<Pair<Int, ByteArray>>()
        fields += 1 to recipientTag
        fields += 2 to ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(expiry.toLong()).array()
        fields += 3 to ciphertext
        if (copies > 1u) fields += 4 to byteArrayOf(copies.toByte())
        val size = fields.sumOf { 3 + it.second.size }
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        fields.forEach { (type, value) ->
            buffer.put(type.toByte())
            buffer.putShort(value.size.toShort())
            buffer.put(value)
        }
        return buffer.array()
    }

    fun matchesRecipient(noiseStaticKey: ByteArray, nowMs: Long): Boolean {
        val day = epochDay(nowMs)
        return listOf(day - 1u, day, day + 1u).any {
            recipientTag.contentEquals(recipientTag(noiseStaticKey, it))
        }
    }

    override fun equals(other: Any?): Boolean = other is CourierEnvelope &&
        recipientTag.contentEquals(other.recipientTag) && expiry == other.expiry &&
        ciphertext.contentEquals(other.ciphertext) && copies == other.copies

    override fun hashCode(): Int = 31 * (31 * recipientTag.contentHashCode() + expiry.hashCode()) +
        31 * ciphertext.contentHashCode() + copies.hashCode()
}
