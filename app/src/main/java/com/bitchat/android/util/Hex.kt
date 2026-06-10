package com.bitchat.android.util

object Hex {
    private val digits = "0123456789abcdef".toCharArray()

    fun encode(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        var index = 0
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            chars[index++] = digits[value ushr 4]
            chars[index++] = digits[value and 0x0F]
        }
        return String(chars)
    }

    fun decode(hex: String, allowOddLength: Boolean = false): ByteArray? {
        val clean = hex.trim()
        val normalized = when {
            clean.length % 2 == 0 -> clean
            allowOddLength -> "0$clean"
            else -> return null
        }

        val output = ByteArray(normalized.length / 2)
        var inputIndex = 0
        var outputIndex = 0
        while (inputIndex < normalized.length) {
            val high = normalized[inputIndex].digitToIntOrNull(16) ?: return null
            val low = normalized[inputIndex + 1].digitToIntOrNull(16) ?: return null
            output[outputIndex++] = ((high shl 4) or low).toByte()
            inputIndex += 2
        }
        return output
    }

    fun decodeOrThrow(hex: String, allowOddLength: Boolean = false): ByteArray {
        return decode(hex, allowOddLength)
            ?: throw IllegalArgumentException("Invalid hex string")
    }
}

fun ByteArray.toHexString(): String = Hex.encode(this)

fun String.hexToByteArray(): ByteArray = Hex.decodeOrThrow(this)

fun String.hexToByteArrayOrNull(allowOddLength: Boolean = false): ByteArray? {
    return Hex.decode(this, allowOddLength)
}
