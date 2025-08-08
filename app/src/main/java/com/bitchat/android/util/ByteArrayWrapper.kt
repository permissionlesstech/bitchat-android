package com.bitchat.android.util

import java.util.Arrays

/**
 * A wrapper class for ByteArray to allow it to be used as a key in HashMaps.
 * The default ByteArray does not override equals() and hashCode() based on content.
 *
 * @param bytes The byte array to wrap.
 */
data class ByteArrayWrapper(val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ByteArrayWrapper
        return Arrays.equals(bytes, other.bytes)
    }

    override fun hashCode(): Int {
        return Arrays.hashCode(bytes)
    }

    fun toHexString(): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
