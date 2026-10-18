package com.bitchat.android.nostr

import java.security.MessageDigest

/** Cross-platform stable identity for a public mesh radio/bridge copy. */
object MeshMessageIdentity {
    fun stableId(senderIdHex: String, timestampMs: Long, content: String): String {
        val input = "${senderIdHex.lowercase()}|$timestampMs|${content.trim()}"
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(32)
    }
}
