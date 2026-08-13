package com.bitchat.android.mesh

import android.content.Context
import com.bitchat.android.model.CourierEnvelope
import com.bitchat.android.services.AndroidConversationStorageCipher
import com.bitchat.android.services.ConversationStorageCipher
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.Base64
import java.nio.file.StandardCopyOption

enum class CourierDepositTier { FAVORITE, VERIFIED }

/** Bounded persistent mailbag for opaque envelopes deposited by other peers. */
internal class CourierStore(
    context: Context,
    private val cipher: ConversationStorageCipher = AndroidConversationStorageCipher(KEY_ALIAS),
    private val now: () -> Long = System::currentTimeMillis
) {
    private data class Stored(
        val encoded: String,
        val depositorKey: String,
        val tier: CourierDepositTier,
        var copies: Int,
        val sprayedTo: MutableSet<String> = mutableSetOf(),
        var lastRemoteHandoverAtMs: Long = 0
    )

    companion object {
        private const val KEY_ALIAS = "bitchat_courier_store_v1"
        private val AAD = "bitchat-courier-store-v1".toByteArray(Charsets.UTF_8)
        private const val MAX_ENVELOPES = 40
        private const val MAX_VERIFIED_ENVELOPES = 20
        private const val MAX_PER_FAVORITE = 5
        private const val MAX_PER_VERIFIED = 2
        private const val EXPIRY_SLACK_MS = 60 * 60 * 1000L
        private const val REMOTE_HANDOVER_COOLDOWN_MS = 10 * 60 * 1000L
    }

    private val gson = Gson()
    private val file = File(context.applicationContext.filesDir, "courier-store.sealed")
    private val stored = load()

    @Synchronized
    fun deposit(envelope: CourierEnvelope, depositorNoiseKey: ByteArray, tier: CourierDepositTier): Boolean {
        pruneExpired()
        val nowMs = now()
        if (envelope.expiry.toLong() <= nowMs ||
            envelope.expiry.toLong() > nowMs + CourierEnvelope.MAX_LIFETIME_MS + EXPIRY_SLACK_MS
        ) return false
        val encodedBytes = envelope.encode() ?: return false
        val encoded = Base64.getEncoder().encodeToString(encodedBytes)
        if (stored.any { it.envelope()?.ciphertext?.contentEquals(envelope.ciphertext) == true }) return true
        val depositor = depositorNoiseKey.toHex()
        val perDepositor = if (tier == CourierDepositTier.FAVORITE) MAX_PER_FAVORITE else MAX_PER_VERIFIED
        if (stored.count { it.depositorKey == depositor && it.tier == tier } >= perDepositor) return false
        if (tier == CourierDepositTier.VERIFIED && stored.count { it.tier == tier } >= MAX_VERIFIED_ENVELOPES) {
            stored.removeAt(stored.indexOfFirst { it.tier == CourierDepositTier.VERIFIED })
        }
        if (stored.size >= MAX_ENVELOPES) {
            val verifiedIndex = stored.indexOfFirst { it.tier == CourierDepositTier.VERIFIED }
            if (tier == CourierDepositTier.VERIFIED && verifiedIndex < 0) return false
            val index = verifiedIndex.takeIf { it >= 0 } ?: 0
            stored.removeAt(index)
        }
        stored += Stored(encoded, depositor, tier, envelope.copies.toInt())
        persist()
        return true
    }

    @Synchronized
    fun copiesForRecipient(recipientNoiseKey: ByteArray): List<CourierEnvelope> {
        pruneExpired()
        val nowMs = now()
        return stored.mapNotNull { record ->
            record.envelope()
                ?.takeIf { it.matchesRecipient(recipientNoiseKey, nowMs) }
                ?.copy(copies = 1u)
        }
    }

    @Synchronized
    fun remove(envelope: CourierEnvelope): Boolean {
        val removed = stored.removeAll {
            it.envelope()?.ciphertext?.contentEquals(envelope.ciphertext) == true
        }
        if (removed) persist()
        return removed
    }

    @Synchronized
    fun copiesForRemoteHandover(recipientNoiseKey: ByteArray): List<CourierEnvelope> {
        pruneExpired()
        val nowMs = now()
        val result = mutableListOf<CourierEnvelope>()
        stored.forEach { record ->
            val envelope = record.envelope() ?: return@forEach
            if (envelope.matchesRecipient(recipientNoiseKey, nowMs) &&
                nowMs - record.lastRemoteHandoverAtMs >= REMOTE_HANDOVER_COOLDOWN_MS
            ) {
                record.lastRemoteHandoverAtMs = nowMs
                result += envelope.copy(copies = 1u)
            }
        }
        if (result.isNotEmpty()) persist()
        return result
    }

    @Synchronized
    fun sprayCopiesFor(courierNoiseKey: ByteArray): List<CourierEnvelope> {
        pruneExpired()
        val key = courierNoiseKey.toHex()
        val nowMs = now()
        val courierTags = listOf(-1, 0, 1).map {
            CourierEnvelope.recipientTag(courierNoiseKey, CourierEnvelope.epochDay(nowMs) + it.toUInt())
        }
        val result = mutableListOf<CourierEnvelope>()
        stored.forEach { record ->
            val envelope = record.envelope() ?: return@forEach
            if (record.copies <= 1 || record.depositorKey == key || key in record.sprayedTo ||
                courierTags.any { it.contentEquals(envelope.recipientTag) }
            ) return@forEach
            val given = record.copies / 2
            result += envelope.copy(copies = given.toUByte())
        }
        return result
    }

    @Synchronized
    fun commitSpray(envelope: CourierEnvelope, courierNoiseKey: ByteArray): Boolean {
        val key = courierNoiseKey.toHex()
        val record = stored.firstOrNull {
            it.envelope()?.ciphertext?.contentEquals(envelope.ciphertext) == true
        } ?: return false
        if (record.copies <= 1 || key in record.sprayedTo) return false
        val given = record.copies / 2
        if (given != envelope.copies.toInt()) return false
        record.copies -= given
        record.sprayedTo += key
        persist()
        return true
    }

    @Synchronized
    fun wipe() {
        stored.clear()
        file.delete()
        cipher.destroyKey()
    }

    private fun pruneExpired() {
        val nowMs = now().toULong()
        if (stored.removeAll { (it.envelope()?.expiry ?: 0u) <= nowMs }) persist()
    }

    private fun Stored.envelope(): CourierEnvelope? = try {
        CourierEnvelope.decode(Base64.getDecoder().decode(encoded))
    } catch (_: Exception) { null }

    private fun load(): MutableList<Stored> = try {
        if (!file.exists()) return mutableListOf()
        val plaintext = cipher.decrypt(file.readBytes(), AAD)
        val type = object : TypeToken<MutableList<Stored>>() {}.type
        gson.fromJson<MutableList<Stored>>(plaintext.toString(Charsets.UTF_8), type) ?: mutableListOf()
    } catch (_: Exception) { mutableListOf() }

    private fun persist() {
        if (stored.isEmpty()) {
            file.delete()
            return
        }
        val encrypted = cipher.encrypt(gson.toJson(stored).toByteArray(Charsets.UTF_8), AAD)
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeBytes(encrypted)
        try {
            java.nio.file.Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: Exception) {
            java.nio.file.Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
