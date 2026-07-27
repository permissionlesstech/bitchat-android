package com.bitchat.android.model

import java.io.ByteArrayOutputStream

/**
 * Minimal unsigned 16-bit big-endian TLV codec used by bridge wire models.
 *
 * Semantic validation intentionally remains in each model. This helper only
 * owns framing so every decoder rejects truncated and trailing data the same
 * way.
 */
internal object Tlv16Codec {
    data class Field(val type: Int, val value: ByteArray)

    fun encode(vararg fields: Field): ByteArray? {
        val output = ByteArrayOutputStream(
            fields.sumOf { HEADER_SIZE + it.value.size }
        )
        fields.forEach { field ->
            if (field.type !in 0..0xFF || field.value.size > 0xFFFF) return null
            output.write(field.type)
            output.write((field.value.size ushr 8) and 0xFF)
            output.write(field.value.size and 0xFF)
            output.write(field.value)
        }
        return output.toByteArray()
    }

    fun decode(data: ByteArray): List<Field>? {
        val fields = mutableListOf<Field>()
        var offset = 0
        while (offset < data.size) {
            if (data.size - offset < HEADER_SIZE) return null
            val type = data[offset].toInt() and 0xFF
            val length =
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                    (data[offset + 2].toInt() and 0xFF)
            offset += HEADER_SIZE
            if (length > data.size - offset) return null
            fields += Field(type, data.copyOfRange(offset, offset + length))
            offset += length
        }
        return fields
    }

    private const val HEADER_SIZE = 3
}
