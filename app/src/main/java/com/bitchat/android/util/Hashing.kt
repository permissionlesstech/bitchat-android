package com.bitchat.android.util

import java.io.InputStream
import java.security.MessageDigest

object Hashing {
    fun sha256(bytes: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(bytes)
    }

    fun sha256Hex(bytes: ByteArray): String {
        return Hex.encode(sha256(bytes))
    }

    fun sha256Hex(text: String): String {
        return sha256Hex(text.toByteArray(Charsets.UTF_8))
    }

    fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }

        return Hex.encode(digest.digest())
    }
}
