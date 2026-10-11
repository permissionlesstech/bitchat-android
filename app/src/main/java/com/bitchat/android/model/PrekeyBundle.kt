package com.bitchat.android.model

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Signed batch of one-time Curve25519 keys carried by MessageType.PREKEY_BUNDLE (0x24).
 *
 * The canonical signing bytes and TLV representation intentionally match the
 * iOS BitFoundation implementation byte-for-byte.
 */
data class PrekeyBundle(
    val noiseStaticPublicKey: ByteArray,
    val prekeys: List<Prekey>,
    val generatedAt: Long,
    val signature: ByteArray
) {
    data class Prekey(val id: Long, val publicKey: ByteArray) {
        init {
            require(id in 0..0xFFFF_FFFFL)
            require(publicKey.size == KEY_LENGTH)
        }

        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Prekey && id == other.id && publicKey.contentEquals(other.publicKey))

        override fun hashCode(): Int = 31 * id.hashCode() + publicKey.contentHashCode()
    }

    fun signableBytes(): ByteArray {
        val output = ByteArrayOutputStream(
            1 + SIGNING_CONTEXT.size + KEY_LENGTH + 1 + prekeys.size * PREKEY_ENTRY_LENGTH + Long.SIZE_BYTES
        )
        output.write(SIGNING_CONTEXT.size)
        output.write(SIGNING_CONTEXT)
        output.write(fixedKey(noiseStaticPublicKey))
        output.write(prekeys.size.coerceAtMost(0xFF))
        prekeys.take(0xFF).forEach { prekey ->
            output.write(uint32Bytes(prekey.id))
            output.write(fixedKey(prekey.publicKey))
        }
        output.write(uint64Bytes(generatedAt))
        return output.toByteArray()
    }

    fun encode(): ByteArray? {
        if (noiseStaticPublicKey.size != KEY_LENGTH ||
            signature.size != SIGNATURE_LENGTH ||
            prekeys.isEmpty() ||
            prekeys.size > MAX_PREKEYS ||
            prekeys.map { it.id }.distinct().size != prekeys.size
        ) {
            return null
        }

        val entries = ByteArrayOutputStream(prekeys.size * PREKEY_ENTRY_LENGTH)
        prekeys.forEach { prekey ->
            if (prekey.publicKey.size != KEY_LENGTH || prekey.id !in 0..0xFFFF_FFFFL) return null
            entries.write(uint32Bytes(prekey.id))
            entries.write(prekey.publicKey)
        }

        return Tlv16Codec.encode(
            Tlv16Codec.Field(TLV_NOISE_STATIC_KEY, noiseStaticPublicKey),
            Tlv16Codec.Field(TLV_PREKEYS, entries.toByteArray()),
            Tlv16Codec.Field(TLV_GENERATED_AT, uint64Bytes(generatedAt)),
            Tlv16Codec.Field(TLV_SIGNATURE, signature)
        )
    }

    companion object {
        const val KEY_LENGTH = 32
        const val SIGNATURE_LENGTH = 64
        const val MAX_PREKEYS = 8
        private const val PREKEY_ENTRY_LENGTH = 4 + KEY_LENGTH

        private val SIGNING_CONTEXT = "bitchat-prekey-bundle-v1".toByteArray(Charsets.UTF_8)
        private const val TLV_NOISE_STATIC_KEY = 0x01
        private const val TLV_PREKEYS = 0x02
        private const val TLV_GENERATED_AT = 0x03
        private const val TLV_SIGNATURE = 0x04

        fun decode(data: ByteArray): PrekeyBundle? {
            var noiseStaticKey: ByteArray? = null
            var prekeys: List<Prekey>? = null
            var generatedAt: Long? = null
            var signature: ByteArray? = null

            Tlv16Codec.decode(data)?.forEach { field ->
                when (field.type) {
                    TLV_NOISE_STATIC_KEY -> {
                        if (field.value.size != KEY_LENGTH) return null
                        noiseStaticKey = field.value
                    }
                    TLV_PREKEYS -> {
                        if (field.value.isEmpty() ||
                            field.value.size % PREKEY_ENTRY_LENGTH != 0 ||
                            field.value.size / PREKEY_ENTRY_LENGTH > MAX_PREKEYS
                        ) {
                            return null
                        }
                        val parsed = mutableListOf<Prekey>()
                        var entryOffset = 0
                        while (entryOffset < field.value.size) {
                            val id = ByteBuffer.wrap(field.value, entryOffset, Int.SIZE_BYTES)
                                .order(ByteOrder.BIG_ENDIAN)
                                .int.toLong() and 0xFFFF_FFFFL
                            entryOffset += Int.SIZE_BYTES
                            val publicKey =
                                field.value.copyOfRange(entryOffset, entryOffset + KEY_LENGTH)
                            entryOffset += KEY_LENGTH
                            parsed += Prekey(id, publicKey)
                        }
                        if (parsed.map { it.id }.distinct().size != parsed.size) return null
                        prekeys = parsed
                    }
                    TLV_GENERATED_AT -> {
                        if (field.value.size != Long.SIZE_BYTES) return null
                        generatedAt = ByteBuffer.wrap(field.value).order(ByteOrder.BIG_ENDIAN).long
                    }
                    TLV_SIGNATURE -> {
                        if (field.value.size != SIGNATURE_LENGTH) return null
                        signature = field.value
                    }
                }
            } ?: return null

            return runCatching {
                PrekeyBundle(
                    noiseStaticPublicKey = noiseStaticKey ?: return null,
                    prekeys = prekeys?.takeIf { it.isNotEmpty() } ?: return null,
                    generatedAt = generatedAt ?: return null,
                    signature = signature ?: return null
                )
            }.getOrNull()
        }

        private fun uint32Bytes(value: Long): ByteArray =
            ByteBuffer.allocate(Int.SIZE_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(value.toInt())
                .array()

        private fun uint64Bytes(value: Long): ByteArray =
            ByteBuffer.allocate(Long.SIZE_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(value)
                .array()

        private fun fixedKey(key: ByteArray): ByteArray =
            key.copyOf(KEY_LENGTH)
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is PrekeyBundle &&
                noiseStaticPublicKey.contentEquals(other.noiseStaticPublicKey) &&
                prekeys == other.prekeys &&
                generatedAt == other.generatedAt &&
                signature.contentEquals(other.signature))

    override fun hashCode(): Int {
        var result = noiseStaticPublicKey.contentHashCode()
        result = 31 * result + prekeys.hashCode()
        result = 31 * result + generatedAt.hashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }
}
