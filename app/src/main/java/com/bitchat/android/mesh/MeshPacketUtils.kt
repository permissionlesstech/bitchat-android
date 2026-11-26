package com.bitchat.android.mesh

import com.bitchat.android.util.hexNibbleOrNegative
import com.bitchat.android.util.toHexString
import java.security.MessageDigest

/**
 * Shared helpers for mesh packet handling.
 */
object MeshPacketUtils {
    private val sha256ThreadLocal = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

    /**
     * Convert hex string peer ID to binary data (8 bytes), matching iOS behavior.
     */
    fun hexStringToByteArray(hexString: String): ByteArray {
        val result = ByteArray(8)
        val len = hexString.length
        var pos = 0
        var index = 0

        while (pos + 1 < len && index < 8) {
            val hi = hexNibbleOrNegative(hexString[pos])
            val lo = hexNibbleOrNegative(hexString[pos + 1])
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
