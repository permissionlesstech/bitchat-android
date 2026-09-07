package com.bitchat.android.services

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.nio.file.StandardCopyOption

/** Keystore-sealed persistence for private messages awaiting final acknowledgement. */
internal class MessageOutboxStore(
    context: Context,
    private val cipher: ConversationStorageCipher = AndroidConversationStorageCipher(KEY_ALIAS)
) {
    data class Entry(
        val content: String,
        val nickname: String,
        val messageID: String,
        val enqueuedAtMs: Long,
        var sendAttempts: Int = 0,
        var lastAttemptAtMs: Long = 0,
        var bridgeDeposited: Boolean = false,
        var lastBridgeAttemptAtMs: Long = 0,
        val depositedCourierKeys: MutableSet<String> = mutableSetOf()
    )

    companion object {
        private const val KEY_ALIAS = "bitchat_message_outbox_v1"
        private val AAD = "bitchat-message-outbox-v1".toByteArray(Charsets.UTF_8)
    }

    private val gson = Gson()
    private val file = File(context.applicationContext.filesDir, "message-outbox.sealed")

    @Synchronized
    fun load(): MutableMap<String, MutableList<Entry>> = try {
        if (!file.exists()) return mutableMapOf()
        val plaintext = cipher.decrypt(file.readBytes(), AAD)
        val type = object : TypeToken<MutableMap<String, MutableList<Entry>>>() {}.type
        gson.fromJson<MutableMap<String, MutableList<Entry>>>(plaintext.toString(Charsets.UTF_8), type)
            ?: mutableMapOf()
    } catch (_: Exception) {
        mutableMapOf()
    }

    @Synchronized
    fun save(outbox: Map<String, List<Entry>>) {
        if (outbox.values.all { it.isEmpty() }) {
            file.delete()
            return
        }
        val plaintext = gson.toJson(outbox).toByteArray(Charsets.UTF_8)
        val encrypted = cipher.encrypt(plaintext, AAD)
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeBytes(encrypted)
        try {
            java.nio.file.Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (e: Exception) {
            temporary.delete()
            throw IllegalStateException("Failed to persist message outbox", e)
        }
    }

    @Synchronized
    fun wipe() {
        file.delete()
        cipher.destroyKey()
    }
}
