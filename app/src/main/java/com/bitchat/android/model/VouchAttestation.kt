package com.bitchat.android.model

import java.io.ByteArrayOutputStream

/**
 * An iOS-compatible, Ed25519-signed statement that the sender of the enclosing
 * authenticated Noise payload verified [voucheeFingerprint].
 */
data class VouchAttestation(
    val voucheeFingerprint: ByteArray,
    val voucheeSigningKey: ByteArray,
    val timestampMs: Long,
    val signature: ByteArray
) {
    init {
        require(voucheeFingerprint.size == FINGERPRINT_SIZE)
        require(voucheeSigningKey.size == SIGNING_KEY_SIZE)
        require(signature.size == SIGNATURE_SIZE)
    }

    fun signableBytes(): ByteArray = signableBytes(
        voucheeFingerprint,
        voucheeSigningKey,
        timestampMs
    )

    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean {
        val age = nowMs - timestampMs
        return age > MAX_AGE_MS || age < -MAX_CLOCK_SKEW_MS
    }

    fun encode(): ByteArray {
        val output = ByteArrayOutputStream()
        output.writeTlv(TYPE_FINGERPRINT, voucheeFingerprint)
        output.writeTlv(TYPE_SIGNING_KEY, voucheeSigningKey)
        output.writeTlv(TYPE_TIMESTAMP, timestampBytes(timestampMs))
        output.writeTlv(TYPE_SIGNATURE, signature)
        return output.toByteArray()
    }

    override fun equals(other: Any?): Boolean =
        other is VouchAttestation &&
            voucheeFingerprint.contentEquals(other.voucheeFingerprint) &&
            voucheeSigningKey.contentEquals(other.voucheeSigningKey) &&
            timestampMs == other.timestampMs &&
            signature.contentEquals(other.signature)

    override fun hashCode(): Int {
        var result = voucheeFingerprint.contentHashCode()
        result = HASH_MULTIPLIER * result + voucheeSigningKey.contentHashCode()
        result = HASH_MULTIPLIER * result + timestampMs.hashCode()
        return HASH_MULTIPLIER * result + signature.contentHashCode()
    }

    companion object {
        const val MAX_BATCH_COUNT = 16
        const val FINGERPRINT_SIZE = 32
        const val SIGNING_KEY_SIZE = 32
        const val SIGNATURE_SIZE = 64
        private const val DAYS_VALID = 30L
        private const val HOURS_PER_DAY = 24L
        private const val MINUTES_PER_HOUR = 60L
        private const val SECONDS_PER_MINUTE = 60L
        private const val MILLIS_PER_SECOND = 1000L
        const val MAX_AGE_MS =
            DAYS_VALID * HOURS_PER_DAY * MINUTES_PER_HOUR * SECONDS_PER_MINUTE * MILLIS_PER_SECOND
        const val MAX_CLOCK_SKEW_MS =
            MINUTES_PER_HOUR * SECONDS_PER_MINUTE * MILLIS_PER_SECOND

        private const val SIGNING_CONTEXT = "bitchat-vouch-v1"
        private const val TYPE_FINGERPRINT = 0x01
        private const val TYPE_SIGNING_KEY = 0x02
        private const val TYPE_TIMESTAMP = 0x03
        private const val TYPE_SIGNATURE = 0x04
        private const val TLV_HEADER_SIZE = 2
        private const val TLV_LENGTH_SIZE = 1
        private const val BATCH_COUNT_SIZE = 1
        private const val BATCH_ENTRY_LENGTH_SIZE = 2
        private const val BITS_PER_BYTE = 8
        private const val BYTE_MASK = 0xFF
        private const val UINT16_MAX = 0xFFFF
        private const val INITIAL_OFFSET = 0
        private const val INITIAL_TIMESTAMP = 0L
        private const val MINIMUM_TIMESTAMP_MS = 0L
        private const val INITIAL_ENTRY_COUNT = 0
        private const val HASH_MULTIPLIER = 31
        private const val TIMESTAMP_LENGTH = Long.SIZE_BYTES

        fun build(
            voucheeFingerprint: ByteArray,
            voucheeSigningKey: ByteArray,
            timestampMs: Long = System.currentTimeMillis(),
            sign: (ByteArray) -> ByteArray?
        ): VouchAttestation? {
            if (voucheeFingerprint.size != FINGERPRINT_SIZE ||
                voucheeSigningKey.size != SIGNING_KEY_SIZE
            ) return null
            val signature = sign(signableBytes(voucheeFingerprint, voucheeSigningKey, timestampMs))
                ?: return null
            if (signature.size != SIGNATURE_SIZE) return null
            return VouchAttestation(voucheeFingerprint, voucheeSigningKey, timestampMs, signature)
        }

        fun signableBytes(
            voucheeFingerprint: ByteArray,
            voucheeSigningKey: ByteArray,
            timestampMs: Long
        ): ByteArray = SIGNING_CONTEXT.toByteArray(Charsets.UTF_8) +
            voucheeFingerprint + voucheeSigningKey + timestampBytes(timestampMs)

        fun decode(data: ByteArray): VouchAttestation? {
            var offset = INITIAL_OFFSET
            var fingerprint: ByteArray? = null
            var signingKey: ByteArray? = null
            var timestamp: Long? = null
            var signature: ByteArray? = null
            while (offset < data.size) {
                if (offset + TLV_HEADER_SIZE > data.size) return null
                val type = data[offset++].toInt() and BYTE_MASK
                val length = data[offset++].toInt() and BYTE_MASK
                if (offset + length > data.size) return null
                val value = data.copyOfRange(offset, offset + length)
                offset += length
                when (type) {
                    TYPE_FINGERPRINT -> if (length == FINGERPRINT_SIZE) fingerprint = value else return null
                    TYPE_SIGNING_KEY -> if (length == SIGNING_KEY_SIZE) signingKey = value else return null
                    TYPE_TIMESTAMP -> if (length == TIMESTAMP_LENGTH) {
                        val decodedTimestamp = value.fold(INITIAL_TIMESTAMP) { result, byte ->
                            (result shl BITS_PER_BYTE) or (byte.toLong() and BYTE_MASK.toLong())
                        }
                        if (decodedTimestamp < MINIMUM_TIMESTAMP_MS) return null
                        timestamp = decodedTimestamp
                    } else return null
                    TYPE_SIGNATURE -> if (length == SIGNATURE_SIZE) signature = value else return null
                }
            }
            return VouchAttestation(
                fingerprint ?: return null,
                signingKey ?: return null,
                timestamp ?: return null,
                signature ?: return null
            )
        }

        fun encodeList(attestations: List<VouchAttestation>): ByteArray? {
            if (attestations.isEmpty() || attestations.size > MAX_BATCH_COUNT) return null
            val output = ByteArrayOutputStream()
            output.write(attestations.size)
            attestations.forEach { attestation ->
                val encoded = attestation.encode()
                if (encoded.size > UINT16_MAX) return null
                output.write(encoded.size ushr BITS_PER_BYTE)
                output.write(encoded.size and BYTE_MASK)
                output.write(encoded)
            }
            return output.toByteArray()
        }

        fun decodeList(data: ByteArray): List<VouchAttestation> {
            if (data.size <= BATCH_COUNT_SIZE) return emptyList()
            val limit = minOf(data[0].toInt() and BYTE_MASK, MAX_BATCH_COUNT)
            val decoded = mutableListOf<VouchAttestation>()
            var offset = BATCH_COUNT_SIZE
            var entriesRead = INITIAL_ENTRY_COUNT
            while (entriesRead < limit && offset < data.size) {
                if (offset + BATCH_ENTRY_LENGTH_SIZE > data.size) break
                val length = ((data[offset].toInt() and BYTE_MASK) shl BITS_PER_BYTE) or
                    (data[offset + TLV_LENGTH_SIZE].toInt() and BYTE_MASK)
                offset += BATCH_ENTRY_LENGTH_SIZE
                if (offset + length > data.size) break
                decode(data.copyOfRange(offset, offset + length))?.let(decoded::add)
                offset += length
                entriesRead++
            }
            return decoded
        }

        private const val LAST_BYTE_INDEX_OFFSET = 1
        private const val TIMESTAMP_HIGH_BIT_OFFSET =
            (TIMESTAMP_LENGTH - LAST_BYTE_INDEX_OFFSET) * BITS_PER_BYTE

        private fun timestampBytes(timestampMs: Long): ByteArray =
            ByteArray(TIMESTAMP_LENGTH) { index ->
                (timestampMs ushr (TIMESTAMP_HIGH_BIT_OFFSET - index * BITS_PER_BYTE)).toByte()
            }

        private fun ByteArrayOutputStream.writeTlv(type: Int, value: ByteArray) {
            write(type)
            write(value.size)
            write(value)
        }
    }
}
