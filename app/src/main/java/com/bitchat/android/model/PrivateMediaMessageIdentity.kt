package com.bitchat.android.model

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Stable, direction-bound IDs matching the iOS private-media receipt contract. */
object PrivateMediaMessageIdentity {
    private val stable = Regex("media-[0-9a-f]{32}")
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val peer = Regex("[0-9a-f]{16}")

    fun isStableID(value: String): Boolean = stable.matches(value)

    fun stableID(senderPeerID: String, recipientPeerID: String, fileName: String?): String? {
        if (fileName.isNullOrEmpty() || '/' in fileName || '\\' in fileName) return null
        val sender = senderPeerID.lowercase()
        val recipient = recipientPeerID.lowercase()
        if (!peer.matches(sender) || !peer.matches(recipient)) return null
        val stem = fileName.substringBeforeLast('.', fileName)
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val isVoice = stem.startsWith("voice_") && extension == "m4a"
        val isImage = stem.startsWith("img_") && extension in setOf("jpg", "jpeg")
        if (!isVoice && !isImage) return null
        val burst = stem.removePrefix("voice_")
        if (!uuid.matches(stem.substringAfterLast('_')) &&
            !(isVoice && Regex("[0-9a-fA-F]{16}").matches(burst))) return null
        val input = ByteArrayOutputStream()
        input.write("bitchat-private-media-message-v1".toByteArray(Charsets.UTF_8))
        listOf(sender, recipient, fileName).forEach { field ->
            val bytes = field.toByteArray(Charsets.UTF_8)
            input.write(ByteBuffer.allocate(4).putInt(bytes.size).array())
            input.write(bytes)
        }
        return "media-" + MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            .take(16).joinToString("") { "%02x".format(it) }
    }
}
