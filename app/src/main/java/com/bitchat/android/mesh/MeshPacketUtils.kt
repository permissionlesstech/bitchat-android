package com.bitchat.android.mesh

import com.bitchat.android.util.toHexString
import java.security.MessageDigest

/**
 * Shared helpers for mesh packet handling.
 */
object MeshPacketUtils {
    private val sha256ThreadLocal = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

    private fun hexNibble(c: Char): Int = when (c) {
        in '0'..'9' -> c.code - '0'.code
        in 'a'..'f' -> c.code - 'a'.code + 10
        in 'A'..'F' -> c.code - 'A'.code + 10
        else -> -1
    }

    /**
     * Convert hex string peer ID to binary data (8 bytes), matching iOS behavior.
     */
    fun hexStringToByteArray(hexString: String): ByteArray {
        val result = ByteArray(8)
        val len = hexString.length
        var pos = 0
        var index = 0

        while (pos + 1 < len && index < 8) {
            val hi = hexNibble(hexString[pos])
            val lo = hexNibble(hexString[pos + 1])
            if (hi >= 0 && lo >= 0) {
                result[index] = ((hi shl 4) or lo).toByte()
            }
            pos += 2
            index++
        }
        return result
    }

    /**
     * Hash payloads to a stable hex ID for transfer tracking.
     */
    fun sha256Hex(bytes: ByteArray): String = try {
        val md = sha256ThreadLocal.get()!!
        md.reset()
        md.update(bytes)
        md.digest().toHexString()
    } catch (_: Exception) {
        bytes.size.toString(16)
    }
}
