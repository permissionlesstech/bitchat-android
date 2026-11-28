package com.bitchat.android.util

import java.security.MessageDigest

private val HEX_CHARS = charArrayOf(
    '0', '1', '2', '3', '4', '5', '6', '7',
    '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
)

/**
 * Extension function to convert a ByteArray to a lowercase hexadecimal string
 * without per-byte Formatter allocations.
 */
fun ByteArray.toHexString(): String = toHexString(size)

fun ByteArray.toHexString(maxBytes: Int): String {
    val count = minOf(size, maxBytes)
    if (count <= 0) return ""
    val out = CharArray(count * 2)
    var j = 0
    for (i in 0 until count) {
        val v = this[i].toInt() and 0xFF
        out[j++] = HEX_CHARS[v ushr 4]
        out[j++] = HEX_CHARS[v and 0x0F]
    }
    return String(out)
}

private val sha256ThreadLocal = ThreadLocal.withInitial {
    MessageDigest.getInstance("SHA-256")
}

/**
 * SHA-256 of these bytes as lowercase hex.
 *
 * Reuses one digest per thread. `MessageDigest.getInstance` plus a
 * per-byte `String.format` was the expensive part of fingerprints,
 * transfer ids, and geohash key fallbacks.
 */
fun ByteArray.sha256Hex(): String {
    val digest = sha256ThreadLocal.get()
    digest.reset()
    return digest.digest(this).toHexString()
}

/**
 * Decode a single ASCII hexadecimal digit into 0..15, or -1 if invalid.
 * Callers preserve their own invalid-character policy on negative values.
 */
fun hexNibbleOrNegative(c: Char): Int = when (c) {
    in '0'..'9' -> c.code - '0'.code
    in 'a'..'f' -> c.code - 'a'.code + 10
    in 'A'..'F' -> c.code - 'A'.code + 10
    else -> -1
}
