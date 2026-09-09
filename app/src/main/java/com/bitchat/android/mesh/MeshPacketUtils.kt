package com.bitchat.android.mesh

/**
 * Shared helpers for mesh packet handling.
 */
object MeshPacketUtils {
    /**
     * Convert hex string peer ID to binary data (8 bytes), matching iOS behavior.
     */
    fun hexStringToByteArray(hexString: String): ByteArray {
        val result = ByteArray(8) { 0 }
        var tempID = hexString
        var index = 0

        while (tempID.length >= 2 && index < 8) {
            val hexByte = tempID.substring(0, 2)
            val byte = hexByte.toIntOrNull(16)?.toByte()
            if (byte != null) {
                result[index] = byte
            }
            tempID = tempID.substring(2)
            index++
        }
        return result
    }

    /**
     * Hash payloads to a stable hex ID for transfer tracking.
     */
    fun sha256Hex(bytes: ByteArray): String = try {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(bytes)
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        bytes.size.toString(16)
    }
}
