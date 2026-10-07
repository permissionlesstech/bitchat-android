package com.bitchat.android.services

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.mesh.PrivateMediaPreparation
import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.model.PrivateMediaMessageIdentity
import com.google.gson.Gson
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One encrypted atomic record per pending media message; no plaintext retransmission spool. */
internal class PrivateMediaOutboxStore(
    context: Context,
    private val cipher: ConversationStorageCipher = AndroidConversationStorageCipher("bitchat_private_media_outbox_v1")
) {
    data class Entry(
        val id: String,
        val conversationID: String,
        val recipientPeerID: String,
        val encodedPacket: String,
        val createdAt: Long,
        val attempts: Int = 1,
        val lastAttemptAt: Long = createdAt
    )

    private val directory = File(context.filesDir, "private-media-outbox")
    private val gson = Gson()

    fun load(): List<Entry> {
        if (!directory.exists()) return emptyList()
        return checkNotNull(directory.listFiles()).filter { PrivateMediaMessageIdentity.isStableID(it.name) }
            .take(MAX_ENTRIES).mapNotNull { file ->
                // A corrupt record stays quarantined in place; it is never retransmitted.
                runCatching {
                    require(file.length() <= MAX_RECORD_BYTES)
                    val plaintext = cipher.decrypt(AtomicFile(file).readFully(), file.name.toByteArray())
                    gson.fromJson(plaintext.toString(Charsets.UTF_8), Entry::class.java)
                        .also { require(it.id == file.name && it.attempts in 1..MAX_ATTEMPTS) }
                }.getOrNull()
            }
    }

    fun save(entry: Entry) {
        require(PrivateMediaMessageIdentity.isStableID(entry.id))
        check(directory.isDirectory || directory.mkdirs())
        val files = checkNotNull(directory.listFiles())
        val file = File(directory, entry.id)
        check(file.exists() || files.size < MAX_ENTRIES)
        val encrypted = cipher.encrypt(gson.toJson(entry).toByteArray(), entry.id.toByteArray())
        require(encrypted.size <= MAX_RECORD_BYTES)
        check(files.sumOf { it.length() } - file.length() + encrypted.size <= MAX_TOTAL_BYTES)
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(encrypted)
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }

    fun remove(id: String) {
        require(PrivateMediaMessageIdentity.isStableID(id))
        AtomicFile(File(directory, id)).delete()
        check(!File(directory, id).exists())
    }

    fun wipe() {
        cipher.destroyKey()
        check(!directory.exists() || directory.deleteRecursively())
    }

    companion object {
        const val MAX_ATTEMPTS = 8
        private const val MAX_ENTRIES = 100
        private const val MAX_RECORD_BYTES = 8 * 1024 * 1024L
        private const val MAX_TOTAL_BYTES = 64 * 1024 * 1024L
    }
}

/** Retries the original file and message identity until a matching recipient acknowledges it. */
class PrivateMediaOutbox private constructor(context: Context) {
    private val store = PrivateMediaOutboxStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var entries: MutableMap<String, PrivateMediaOutboxStore.Entry>? = null
    @Volatile private var paused = false

    init {
        scope.launch {
            while (isActive) {
                delay(15_000)
                runCatching { retryPending() }
            }
        }
    }

    private fun loaded(): MutableMap<String, PrivateMediaOutboxStore.Entry> =
        entries ?: store.load().associateByTo(linkedMapOf()) { it.id }.also { entries = it }

    suspend fun enqueue(id: String, conversationID: String, recipientPeerID: String, packet: BitchatFilePacket): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (paused) return@withLock false
                runCatching {
                    val encoded = checkNotNull(packet.encode())
                    val entry = PrivateMediaOutboxStore.Entry(id, conversationID, recipientPeerID,
                        Base64.encodeToString(encoded, Base64.NO_WRAP), System.currentTimeMillis())
                    store.save(entry)
                    loaded()[id] = entry
                    true
                }.getOrDefault(false)
            }
        }

    fun acknowledge(id: String, peerID: String) {
        if (!PrivateMediaMessageIdentity.isStableID(id)) return
        scope.launch {
            mutex.withLock {
                val entry = loaded()[id] ?: return@withLock
                if (entry.recipientPeerID != peerID) return@withLock
                runCatching { store.remove(id) }.onSuccess { loaded().remove(id) }
            }
        }
    }

    suspend fun wipe() {
        paused = true
        withContext(Dispatchers.IO) {
            mutex.withLock {
                store.wipe()
                entries = linkedMapOf()
            }
        }
    }

    fun resume() { paused = false }

    private suspend fun retryPending() = mutex.withLock {
        if (paused) return@withLock
        val mesh = com.bitchat.android.service.MeshServiceHolder.unifiedMeshService ?: return@withLock
        val now = System.currentTimeMillis()
        loaded().values.toList().forEach { entry ->
            if (AppStateStore.privateMediaReceiptState(entry.id) == PrivateMediaReceiptState.TOMBSTONED) {
                store.remove(entry.id)
                loaded().remove(entry.id)
                return@forEach
            }
            if (now - entry.createdAt > 24 * 60 * 60 * 1000L ||
                (entry.attempts >= PrivateMediaOutboxStore.MAX_ATTEMPTS && now - entry.lastAttemptAt >= 300_000)) {
                AppStateStore.updatePrivateMessageStatus(entry.id, DeliveryStatus.Failed("No delivery receipt received"))
                store.remove(entry.id)
                loaded().remove(entry.id)
                return@forEach
            }
            if (entry.attempts >= PrivateMediaOutboxStore.MAX_ATTEMPTS) return@forEach
            val interval = (30_000L shl (entry.attempts - 1).coerceAtMost(4)).coerceAtMost(300_000L)
            if (now - entry.lastAttemptAt < interval) return@forEach
            val recipient = ContactDirectory.resolve(entry.conversationID).meshPeerID ?: return@forEach
            if (recipient != entry.recipientPeerID || !mesh.supportsPrivateMediaReceipts(recipient)) return@forEach
            val encoded = Base64.decode(entry.encodedPacket, Base64.NO_WRAP)
            val packet = BitchatFilePacket.decode(encoded) ?: return@forEach
            if (PrivateMediaMessageIdentity.stableID(mesh.myPeerID, recipient, packet.fileName) != entry.id) return@forEach
            val transferID = MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it) }
            val prepared = mesh.prepareFilePrivate(recipient, packet, transferID, allowLegacyFallback = false)
            if (prepared is PrivateMediaPreparation.Ready) {
                val attempted = entry.copy(attempts = entry.attempts + 1, lastAttemptAt = now)
                store.save(attempted)
                loaded()[entry.id] = attempted
                if (prepared.transfer.commit()) AppStateStore.updatePrivateMessageStatus(entry.id, DeliveryStatus.Sent)
            }
        }
    }

    companion object {
        @Volatile private var instance: PrivateMediaOutbox? = null
        fun initialize(context: Context): PrivateMediaOutbox = instance ?: synchronized(this) {
            instance ?: PrivateMediaOutbox(context.applicationContext).also { instance = it }
        }
        fun tryGetInstance(): PrivateMediaOutbox? = instance
    }
}
