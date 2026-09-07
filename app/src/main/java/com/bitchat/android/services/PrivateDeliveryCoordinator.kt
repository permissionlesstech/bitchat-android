package com.bitchat.android.services

import android.content.Context
import android.util.Log
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.nostr.NostrTransport
import com.bitchat.android.ui.DataManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One process-owned worker for persisted messages, relationship controls and receipts. */
class PrivateDeliveryCoordinator internal constructor(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val repositoryProvider: () -> ConversationRepository = { ConversationRepository.getInstance(context) },
    private val transportProvider: () -> NostrTransport = { NostrTransport.getInstance(context) },
    private val favoritesProvider: () -> FavoritesPersistenceService = { FavoritesPersistenceService.shared },
    private val resolve: (String) -> ContactDirectory.ContactResolution = ContactDirectory::resolve,
    private val clock: () -> Long = System::currentTimeMillis
) {
    companion object {
        @Volatile private var instance: PrivateDeliveryCoordinator? = null
        fun getInstance(context: Context): PrivateDeliveryCoordinator = instance ?: synchronized(this) {
            instance ?: PrivateDeliveryCoordinator(context.applicationContext).also { instance = it }
        }
    }

    private val mutex = Mutex()
    private val wakeups = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private val repository get() = repositoryProvider()
    private val transport get() = transportProvider()
    @Volatile private var mesh: MeshService? = null
    @Volatile private var enabled = false
    private var worker: Job? = null
    @Volatile private var generation = 0L

    fun bindMesh(service: MeshService) {
        mesh = service
        ContactDirectory.initialize(context) { mesh }
        transport.senderPeerID = service.myPeerID
        start()
    }

    @Synchronized fun start() {
        enabled = true
        if (worker?.isActive == true) return
        worker = scope.launch {
            while (isActive && enabled) {
                try { drain() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { Log.w("PrivateDelivery", "Delivery worker will retry") }
                withTimeoutOrNull(2_000) { wakeups.receive() }
            }
        }
    }

    @Synchronized fun stop() {
        enabled = false
        generation++
        worker?.cancel()
        worker = null
    }

    fun wake() { if (enabled) wakeups.trySend(Unit) }

    fun enqueue(job: PrivateDeliveryJob) {
        val token = generation
        scope.launch {
            try {
                mutex.withLock {
                    if (token != generation) return@withLock
                    if (job.kind == PrivateDeliveryJob.Kind.MESSAGE && repository.storedMessage(job.conversationID, job.messageID) == null) return@withLock
                    val existing = repository.deliveryJobs().firstOrNull { it.id == job.id }
                    if (job.kind == PrivateDeliveryJob.Kind.MESSAGE && existing != null) {
                        repository.updateDelivery(existing.copy(recipientPubkey = job.recipientPubkey ?: existing.recipientPubkey,
                            sourceGeohash = job.sourceGeohash ?: existing.sourceGeohash))
                    } else repository.saveDelivery(job, replace = job.kind == PrivateDeliveryJob.Kind.FAVORITE)
                }
                wake()
            } catch (_: Exception) {
                if (job.kind == PrivateDeliveryJob.Kind.MESSAGE) {
                    AppStateStore.updatePrivateMessageStatus(job.messageID, DeliveryStatus.Failed("Unable to queue message"))
                }
            }
        }
    }

    private suspend fun drain() = mutex.withLock {
        if (!enabled) return@withLock
        val token = generation
        val queued = repository.deliveryJobs().associateBy { it.id }
        val data = DataManager(context).apply { loadBlockedUsers(); loadGeohashBlockedUsers() }
        for (relationship in favoritesProvider().getAllRelationships()) {
            val controlID = relationship.pendingControlID ?: continue
            val conversation = ContactIdentityResolver.contactConversationIdForNoiseKey(relationship.peerNoisePublicKey)
            if (data.isUserBlocked(ContactIdentityResolver.fingerprintHex(relationship.peerNoisePublicKey))) continue
            val id = "favorite:$conversation"
            val existing = queued[id]
            if (existing?.messageID == controlID) continue
            val identity = com.bitchat.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(context) ?: continue
            try {
                repository.saveDelivery(PrivateDeliveryJob(id, conversation, controlID, PrivateDeliveryJob.Kind.FAVORITE,
                    com.bitchat.android.favorites.FavoriteControlMessage.encode(relationship.isFavorite, identity.npub),
                    relationship.peerNickname, createdAt = relationship.pendingControlTimestamp.takeIf { it > 0 } ?: relationship.lastUpdated.time))
            } catch (_: Exception) {
                // The relationship retains this intent; drain existing jobs to free capacity.
                break
            }
        }
        coroutineScope {
            repository.deliveryJobs().filter { it.nextAttemptAt <= clock() }.take(4).forEach { job ->
                launch { deliver(job, token, data) }
            }
        }
    }

    private suspend fun deliver(original: PrivateDeliveryJob, token: Long, data: DataManager) {
        if (!enabled || token != generation) return
        val now = clock()
        if (original.nextAttemptAt > now) return
        val contact = resolve(original.conversationID)
        val job = original.copy(conversationID = contact.conversationID)
        val blocked = contact.noisePublicKey?.let { data.isUserBlocked(ContactIdentityResolver.fingerprintHex(it)) } == true ||
            job.recipientPubkey?.let(data::isGeohashUserBlocked) == true
        if (blocked || now - job.createdAt >= PrivateDeliveryJob.RETENTION_MS) {
            repository.removeDelivery(job.id)
            if (job.kind == PrivateDeliveryJob.Kind.FAVORITE) favoritesProvider().acknowledgeLocalControl(job.conversationID, job.messageID)
            if (job.kind == PrivateDeliveryJob.Kind.MESSAGE) AppStateStore.updatePrivateMessageStatus(
                job.messageID, DeliveryStatus.Failed(if (blocked) "Contact blocked" else "Delivery expired")
            )
            return
        }
        if (job.kind == PrivateDeliveryJob.Kind.MESSAGE) {
            val stored = repository.storedMessage(job.conversationID, job.messageID)
            if (stored == null || stored.deliveryStatus is DeliveryStatus.Delivered || stored.deliveryStatus is DeliveryStatus.Read) {
                repository.removeDelivery(job.id)
                return
            }
        }
        val service = mesh
        val peer = contact.meshPeerID
        val ready = peer != null && service != null && service.getPeerInfo(peer)?.isConnected == true && service.hasEstablishedSession(peer)
        // One mesh attempt, then Nostr after a bounded acknowledgement window. Subsequent mesh
        // attempts remain possible when no mutual Nostr route exists.
        val canNostr = job.sourceGeohash != null ||
            (contact.nostrPubkey != null && (contact.isMutualFavorite || job.kind != PrivateDeliveryJob.Kind.MESSAGE))
        try {
            if (ready && job.kind != PrivateDeliveryJob.Kind.DELIVERED && (job.attempts == 0 || !canNostr)) {
                if (!repository.updateDelivery(job.copy(attempts = job.attempts + 1, nextAttemptAt = now + PrivateDeliveryJob.MESH_ACK_TIMEOUT_MS))) return
                when (job.kind) {
                    PrivateDeliveryJob.Kind.MESSAGE, PrivateDeliveryJob.Kind.FAVORITE -> service!!.sendPrivateMessage(job.content, peer!!, job.nickname, job.messageID)
                    PrivateDeliveryJob.Kind.READ -> service!!.sendReadReceipt(job.messageID, peer!!, job.nickname)
                    PrivateDeliveryJob.Kind.DELIVERED -> Unit // Mesh reception emits its own delivery ACK.
                }
            } else if (canNostr || job.recipientPubkey != null) {
                // Rewrap on later redelivery attempts so a newly published event remains in
                // the recipient's overlap scan. Transport retries within an attempt reuse it.
                val prepared = transport.prepare(if (job.attempts > 1) job.copy(eventJson = null) else job)
                if (repository.deliveryJobs().none { it.id == job.id && it.messageID == job.messageID }) return
                if (!repository.updateDelivery(prepared.copy(attempts = job.attempts + 1, nextAttemptAt = now + retryDelay(job.attempts)))) return
                if (!enabled || token != generation) return
                // Re-resolve consent immediately before publishing, including queued events.
                val current = resolve(job.conversationID)
                if (job.kind == PrivateDeliveryJob.Kind.MESSAGE && job.sourceGeohash == null && !current.isMutualFavorite) return
                if (transport.publish(prepared) && enabled && token == generation) {
                    if (job.kind == PrivateDeliveryJob.Kind.MESSAGE) {
                        AppStateStore.updatePrivateMessageStatus(job.messageID, DeliveryStatus.Sent)
                    } else if (job.kind != PrivateDeliveryJob.Kind.FAVORITE) {
                        repository.removeDelivery(job.id)
                    }
                }
            } else {
                if (peer != null) service?.initiateNoiseHandshake(peer)
                repository.updateDelivery(job.copy(nextAttemptAt = now + 5_000))
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            if (enabled && token == generation && repository.deliveryJobs().any { it.id == job.id && it.messageID == job.messageID }) {
                repository.updateDelivery(job.copy(attempts = job.attempts + 1, nextAttemptAt = now + retryDelay(job.attempts)))
            }
        }
    }

    /** Only the intended authenticated contact can advance an outgoing message. */
    suspend fun acknowledge(conversationID: String, messageID: String, read: Boolean): Boolean =
        AppStateStore.acknowledgePrivateReceipt(conversationID, messageID, read)

    private fun retryDelay(attempts: Int): Long =
        (1_000L shl attempts.coerceIn(0, 8)).coerceAtMost(300_000L) + kotlin.random.Random.nextLong(1_000)
}
