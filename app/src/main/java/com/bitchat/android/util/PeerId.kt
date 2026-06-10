package com.bitchat.android.util

object PeerId {
    const val BYTE_LENGTH = 8
    const val HEX_LENGTH = BYTE_LENGTH * 2

    fun fromBytes(bytes: ByteArray): String {
        return Hex.encode(bytes.copyOf(BYTE_LENGTH))
    }

    fun parse(peerIdHex: String): ByteArray? {
        val clean = peerIdHex.trim()
        if (clean.length != HEX_LENGTH) return null
        return Hex.decode(clean)?.takeIf { it.size == BYTE_LENGTH }
    }

    fun toBytes(peerIdHex: String): ByteArray {
        val result = ByteArray(BYTE_LENGTH)
        val clean = peerIdHex.trim()
        var inputIndex = 0
        var outputIndex = 0

        while (inputIndex + 1 < clean.length && outputIndex < BYTE_LENGTH) {
            val value = clean.substring(inputIndex, inputIndex + 2).toIntOrNull(16)
            if (value != null) {
                result[outputIndex] = value.toByte()
            }
            inputIndex += 2
            outputIndex += 1
        }

        return result
    }

    fun normalize(peerIdHex: String): String {
        return fromBytes(toBytes(peerIdHex))
    }
}
