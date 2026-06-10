package com.bitchat.android.protocol

enum class TlvLengthSize(val byteCount: Int, val maxValue: Long) {
    ONE_BYTE(1, 0xFFL),
    TWO_BYTES(2, 0xFFFFL),
    FOUR_BYTES(4, 0xFFFF_FFFFL)
}

enum class UnknownTlvPolicy {
    SKIP,
    FAIL
}

data class TlvField(
    val type: Int,
    val value: ByteArray
)

class TlvWriter {
    private val output = ArrayList<Byte>()

    fun put(type: Int, value: ByteArray, lengthSize: TlvLengthSize): TlvWriter {
        require(type in 0..0xFF) { "TLV type must fit in one byte" }
        require(value.size.toLong() <= lengthSize.maxValue) {
            "TLV value length ${value.size} exceeds ${lengthSize.maxValue}"
        }

        output.add(type.toByte())
        writeLength(value.size.toLong(), lengthSize)
        output.addAll(value.toList())
        return this
    }

    fun toByteArray(): ByteArray {
        return output.toByteArray()
    }

    private fun writeLength(length: Long, lengthSize: TlvLengthSize) {
        when (lengthSize) {
            TlvLengthSize.ONE_BYTE -> output.add((length and 0xFF).toByte())
            TlvLengthSize.TWO_BYTES -> {
                output.add(((length ushr 8) and 0xFF).toByte())
                output.add((length and 0xFF).toByte())
            }
            TlvLengthSize.FOUR_BYTES -> {
                output.add(((length ushr 24) and 0xFF).toByte())
                output.add(((length ushr 16) and 0xFF).toByte())
                output.add(((length ushr 8) and 0xFF).toByte())
                output.add((length and 0xFF).toByte())
            }
        }
    }
}

object TlvReader {
    fun decode(
        data: ByteArray,
        defaultLengthSize: TlvLengthSize,
        unknownPolicy: UnknownTlvPolicy = UnknownTlvPolicy.SKIP,
        knownTypes: Set<Int>? = null,
        lengthSizeForType: (Int) -> TlvLengthSize = { defaultLengthSize }
    ): List<TlvField>? {
        val fields = ArrayList<TlvField>()
        var offset = 0

        while (offset < data.size) {
            if (data.size - offset < 1) return null
            val type = data[offset].toInt() and 0xFF
            offset += 1

            val lengthSize = lengthSizeForType(type)
            if (data.size - offset < lengthSize.byteCount) return null

            val length = readLength(data, offset, lengthSize) ?: return null
            offset += lengthSize.byteCount

            if (length > Int.MAX_VALUE || data.size - offset < length.toInt()) return null
            val valueLength = length.toInt()
            val value = data.copyOfRange(offset, offset + valueLength)
            offset += valueLength

            if (knownTypes != null && type !in knownTypes) {
                when (unknownPolicy) {
                    UnknownTlvPolicy.SKIP -> continue
                    UnknownTlvPolicy.FAIL -> return null
                }
            }

            fields.add(TlvField(type, value))
        }

        return fields
    }

    private fun readLength(data: ByteArray, offset: Int, lengthSize: TlvLengthSize): Long? {
        return when (lengthSize) {
            TlvLengthSize.ONE_BYTE -> data[offset].toLong() and 0xFFL
            TlvLengthSize.TWO_BYTES -> {
                ((data[offset].toLong() and 0xFFL) shl 8) or
                    (data[offset + 1].toLong() and 0xFFL)
            }
            TlvLengthSize.FOUR_BYTES -> {
                ((data[offset].toLong() and 0xFFL) shl 24) or
                    ((data[offset + 1].toLong() and 0xFFL) shl 16) or
                    ((data[offset + 2].toLong() and 0xFFL) shl 8) or
                    (data[offset + 3].toLong() and 0xFFL)
            }
        }
    }
}
