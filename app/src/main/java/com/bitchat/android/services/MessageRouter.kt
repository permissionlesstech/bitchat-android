package com.bitchat.android.services

import android.content.Context
import android.util.Log
import com.bitchat.android.favorites.FavoriteControlMessage
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.model.ReadReceipt
import com.bitchat.android.nostr.NostrTransport
import com.bitchat.android.util.AppConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Routes messages between local mesh transports and Nostr, matching iOS behavior.
 */
class MessageRouter private constructor(
    private val context: Context,
    private var mesh: MeshService,
    private val nostr: NostrTransport
) {
    enum class RouteResult {
        MESH,
        NOSTR,
        QUEUED,
        DROPPED
    }

    private data class ConversationRetry(
        val handshakeAttempts: Int,
        val nextHandshakeAttemptAtMs: Long
    )

    companion object {
        private const val TAG = "MessageRouter"
        private const val OUTBOX_TICK_MS = AppConstants.Router.OUTBOX_TICK_MS
        private const val OUTBOX_MESSAGE_TTL_MS = AppConstants.Router.OUTBOX_MESSAGE_TTL_MS
        private const val OUTBOX_MAX_PER_PEER = AppConstants.Router.OUTBOX_MAX_PER_PEER
        private const val MAX_COURIERS_PER_MESSAGE = AppConstants.Router.MAX_COURIERS_PER_MESSAGE
        private const val OUTBOX_MAX_TOTAL = 1_000
        private const val OUTBOX_MAX_SEND_ATTEMPTS = 8
        private const val BRIDGE_RETRY_COOLDOWN_MS = 30 * 60 * 1000L
        private val HANDSHAKE_RETRY_BACKOFF_MS = AppConstants.Router.HANDSHAKE_RETRY_BACKOFF_MS

        @Volatile private var INSTANCE: MessageRouter? = null
        internal var disableSchedulerForTesting = false
        internal var outboxStoreFactory: (Context) -> MessageOutboxStore = ::MessageOutboxStore
        fun tryGetInstance(): MessageRouter? = INSTANCE
        fun getInstance(context: Context, mesh: MeshService): MessageRouter {
            val instance = INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val nostr = NostrTransport.getInstance(context)
                    MessageRouter(context.applicationContext, mesh, nostr).also { instance ->
                        // Register for favorites changes to flush outbox
                        try {
                            com.bitchat.android.favorites.FavoritesPersistenceService.shared.addListener(instance.favoriteListener)
                        } catch (_: Exception) {}
                        INSTANCE = instance
                    }
                }
            }
            // Always update mesh reference and sync peer ID, and make sure the retry
            // scheduler is running (it is stopped together with MeshForegroundService).
            instance.mesh = mesh
            instance.nostr.senderPeerID = mesh.myPeerID
            instance.startOutboxScheduler()
            return instance
        }

        internal fun resetForTesting() {
            INSTANCE?.schedulerScope?.cancel()
            INSTANCE = null
        }

        fun panicClear(context: Context) {
            val instance = INSTANCE
            if (instance != null) instance.clearAll()
            else outboxStoreFactory(context.applicationContext).wipe()
        }
    }

    // Outbox: conversationID -> queued messages, oldest first
    private val outboxStore = outboxStoreFactory(context)
    private val outbox = ConcurrentHashMap<String, MutableList<MessageOutboxStore.Entry>>().apply {
        putAll(outboxStore.load())
    }

    // Per-conversation handshake retry state for queued messages
    private val retryState = ConcurrentHashMap<String, ConversationRetry>()

    private val schedulerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var schedulerJob: kotlinx.coroutines.Job? = null

    // Injectable clock for tests
    internal var clock: () -> Long = { System.currentTimeMillis() }

    // Called with the messageID of queued messages that expired or were evicted
    var onMessageExpired: ((String) -> Unit)? = null

    init {
        startOutboxScheduler()
    }

    @Synchronized
    fun clearAll() {
        schedulerJob?.cancel()
        schedulerJob = null
        outbox.clear()
        retryState.clear()
        outboxStore.wipe()
        Log.d(TAG, "Cleared all MessageRouter outbox messages and retry state")
    }

    // Listener for favorites changes to flush outbox when npub mapping appears/changes
    private val favoriteListener = object: com.bitchat.android.favorites.FavoritesChangeListener {

        override fun onFavoriteChanged(noiseKeyHex: String) {
            flushOutboxFor(noiseKeyHex)
            ContactIdentityResolver.peerIdForNoiseKeyHex(noiseKeyHex)?.let { flushOutboxFor(it) }
        }
        override fun onAllCleared() {
        }
    }

    fun sendPrivate(content: String, toPeerID: String, recipientNickname: String, messageID: String): RouteResult {
        val resolution = ContactDirectory.resolve(toPeerID)
        val conversationID = resolution.conversationID
        val meshTarget = resolution.meshPeerID ?: toPeerID.takeIf { ContactIdentityResolver.isMeshPeerId(it) }
        val nostrTarget = resolution.noiseKeyHex ?: toPeerID

        if (com.bitchat.android.nostr.GeohashAliasRegistry.contains(toPeerID)) {
            Log.d(TAG, "Routing PM via Nostr (geohash) to alias ${toPeerID.take(12)}… id=${messageID.take(8)}…")
            val recipientHex = com.bitchat.android.nostr.GeohashAliasRegistry.get(toPeerID)
            if (recipientHex != null) {
                val sourceGeohash = com.bitchat.android.nostr.GeohashConversationRegistry.get(toPeerID)
                nostr.sendPrivateMessageGeohash(content, recipientHex, messageID, sourceGeohash)
                return RouteResult.NOSTR
            }
            return RouteResult.DROPPED
        }

        val hasMesh = meshTarget?.let { isConnected(mesh, it) } == true
        val entry = MessageOutboxStore.Entry(content, recipientNickname, messageID, clock())
        enqueue(conversationID, entry)
        if (meshTarget != null && isReady(mesh, meshTarget)) {
            Log.d(TAG, "Routing PM via mesh to ${meshTarget} msg_id=${messageID.take(8)}…")
            mesh.sendPrivateMessage(content, meshTarget, recipientNickname, messageID)
            markAttempt(conversationID, messageID)
            return RouteResult.MESH
        } else if (canSendViaNostr(nostrTarget)) {
            Log.d(TAG, "Routing PM via Nostr to ${conversationID.take(32)}… msg_id=${messageID.take(8)}…")
            nostr.sendPrivateMessage(content, nostrTarget, recipientNickname, messageID)
            markAttempt(conversationID, messageID)
            val prompt = canDeliverViaNostrPromptly()
            if (!prompt) attemptCourierDeposit(conversationID, entry)
            return if (prompt) RouteResult.NOSTR else RouteResult.QUEUED
        } else {
            Log.d(TAG, "Queued PM for ${conversationID} (no mesh, no Nostr mapping) msg_id=${messageID.take(8)}…")
            attemptCourierDeposit(conversationID, entry)
            Log.d(TAG, "Initiating noise handshake after queueing PM for ${conversationID.take(16)}…")
            if (hasMesh) meshTarget?.let { kickHandshake(conversationID, it, immediate = true) }
            return RouteResult.QUEUED
        }
    }

    fun sendReadReceipt(receipt: ReadReceipt, toPeerID: String) {
        val resolution = ContactDirectory.resolve(toPeerID)
        val meshTarget = resolution.meshPeerID ?: toPeerID.takeIf { ContactIdentityResolver.isMeshPeerId(it) }
        val nostrTarget = resolution.noiseKeyHex ?: toPeerID
        if (meshTarget != null && isReady(mesh, meshTarget)) {
            Log.d(TAG, "Routing READ via mesh to ${meshTarget.take(8)}… id=${receipt.originalMessageID.take(8)}…")
            mesh.sendReadReceipt(receipt.originalMessageID, meshTarget, mesh.getPeerNicknames()[meshTarget] ?: mesh.myPeerID)
        } else {
            Log.d(TAG, "Routing READ via Nostr to ${toPeerID.take(8)}… id=${receipt.originalMessageID.take(8)}…")
            nostr.sendReadReceipt(receipt, nostrTarget)
        }
    }

    fun sendDeliveryAck(messageID: String, toPeerID: String) {
        // Mesh delivery ACKs are sent by the receiver automatically.
        // Only route via Nostr when mesh path isn't available or when this is a geohash alias
        if (com.bitchat.android.nostr.GeohashAliasRegistry.contains(toPeerID)) {
            val recipientHex = com.bitchat.android.nostr.GeohashAliasRegistry.get(toPeerID)
            if (recipientHex != null) {
                nostr.sendDeliveryAckGeohash(messageID, recipientHex, try { com.bitchat.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(context)!! } catch (_: Exception) { return })
                return
            }
        }
        val resolution = ContactDirectory.resolve(toPeerID)
        val meshTarget = resolution.meshPeerID ?: toPeerID.takeIf { ContactIdentityResolver.isMeshPeerId(it) }
        if (!(meshTarget != null && (mesh.getPeerInfo(meshTarget)?.isConnected == true) && mesh.hasEstablishedSession(meshTarget))) {
            nostr.sendDeliveryAck(messageID, resolution.noiseKeyHex ?: toPeerID)
        }
    }

    fun sendFavoriteNotification(toPeerID: String, isFavorite: Boolean) {
        val resolution = ContactDirectory.resolve(toPeerID)
        val meshTarget = resolution.meshPeerID ?: toPeerID.takeIf { ContactIdentityResolver.isMeshPeerId(it) }
        if (meshTarget != null && mesh.getPeerInfo(meshTarget)?.isConnected == true && mesh.hasEstablishedSession(meshTarget)) {
            val myNpub = try { com.bitchat.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(context)?.npub } catch (_: Exception) { null }
            val content = FavoriteControlMessage.encode(isFavorite, myNpub)
            val nickname = mesh.getPeerNicknames()[meshTarget] ?: meshTarget
            mesh.sendPrivateMessage(content, meshTarget, nickname, null)
        } else {
            nostr.sendFavoriteNotification(resolution.noiseKeyHex ?: toPeerID, isFavorite)
        }
    }

    // Flush any queued messages for a specific peerID.
    // All outbox mutations happen under the router monitor so a concurrent enqueue cannot
    // be lost between the empty check and the map removal.
    @Synchronized
    fun flushOutboxFor(peerID: String) {
        val conversationID = ContactDirectory.canonicalConversationId(peerID)
        val queued = outbox[conversationID] ?: outbox[peerID] ?: return
        if (queued.isEmpty()) return
        Log.d(TAG, "Flushing outbox for ${conversationID.take(16)}… count=${queued.size}")
        val iterator = queued.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val resolution = ContactDirectory.resolve(conversationID)
            val meshTarget = resolution.meshPeerID
            val nostrTarget = resolution.noiseKeyHex ?: conversationID
            if (clock() - entry.lastAttemptAtMs < retryDelay(entry.sendAttempts)) continue
            if (entry.sendAttempts >= OUTBOX_MAX_SEND_ATTEMPTS) continue
            if (meshTarget != null && isReady(mesh, meshTarget)) {
                mesh.sendPrivateMessage(entry.content, meshTarget, entry.nickname, entry.messageID)
                entry.sendAttempts++
                entry.lastAttemptAtMs = clock()
            } else if (canSendViaNostr(nostrTarget)) {
                nostr.sendPrivateMessage(entry.content, nostrTarget, entry.nickname, entry.messageID)
                entry.sendAttempts++
                entry.lastAttemptAtMs = clock()
                if (!canDeliverViaNostrPromptly()) attemptCourierDeposit(conversationID, entry)
            }
        }
        if (queued.isEmpty()) {
            outbox.remove(conversationID, queued)
            outbox.remove(peerID, queued)
            retryState.remove(conversationID)
            retryState.remove(peerID)
        }
        persistOutbox()
    }

    // Flush everything (rarely used)
    fun flushAllOutbox() {
        outbox.keys.toList().forEach { flushOutboxFor(it) }
    }

    @Synchronized
    private fun enqueue(conversationID: String, entry: MessageOutboxStore.Entry) {
        val queue = outbox.getOrPut(conversationID) { mutableListOf() }
        if (queue.any { it.messageID == entry.messageID }) return
        queue.add(entry)
        while (queue.size > OUTBOX_MAX_PER_PEER) {
            val evicted = queue.removeAt(0)
            Log.w(TAG, "Outbox full for ${conversationID.take(16)}…; evicting oldest msg_id=${evicted.messageID.take(8)}…")
            notifyExpired(evicted.messageID)
        }
        while (outbox.values.sumOf { it.size } > OUTBOX_MAX_TOTAL) {
            val oldest = outbox.entries
                .flatMap { (id, entries) -> entries.map { id to it } }
                .minByOrNull { it.second.enqueuedAtMs } ?: break
            outbox[oldest.first]?.remove(oldest.second)
            if (outbox[oldest.first].isNullOrEmpty()) outbox.remove(oldest.first)
            notifyExpired(oldest.second.messageID)
        }
        persistOutbox()
    }

    @Synchronized
    fun onMessageAcknowledged(messageID: String, peerID: String) {
        val acknowledgedConversation = ContactDirectory.canonicalConversationId(peerID)
        var changed = false
        outbox.entries.toList().forEach { (conversationID, queue) ->
            if (ContactDirectory.canonicalConversationId(conversationID) != acknowledgedConversation) return@forEach
            changed = queue.removeAll { it.messageID == messageID } || changed
            if (queue.isEmpty()) {
                outbox.remove(conversationID, queue)
                retryState.remove(conversationID)
            }
        }
        if (changed) persistOutbox()
    }

    @Synchronized
    private fun markAttempt(conversationID: String, messageID: String) {
        outbox[conversationID]?.firstOrNull { it.messageID == messageID }?.sendAttempts =
            (outbox[conversationID]?.firstOrNull { it.messageID == messageID }?.sendAttempts ?: 0) + 1
        outbox[conversationID]?.firstOrNull { it.messageID == messageID }?.lastAttemptAtMs = clock()
        persistOutbox()
    }

    private fun retryDelay(attempts: Int): Long = when (attempts) {
        0 -> 0L
        1 -> 30_000L
        2 -> 2 * 60_000L
        else -> 10 * 60_000L
    }

    private fun attemptCourierDeposit(conversationID: String, entry: MessageOutboxStore.Entry) {
        val resolution = ContactDirectory.resolve(conversationID)
        val recipientKey = resolution.noiseKeyHex?.let(ContactIdentityResolver::bytesFromHex) ?: return
        if (!entry.bridgeDeposited &&
            (entry.lastBridgeAttemptAtMs == 0L || clock() - entry.lastBridgeAttemptAtMs >= BRIDGE_RETRY_COOLDOWN_MS)
        ) {
            val submitted = mesh.sendBridgeCourierMessage(entry.content, entry.messageID, recipientKey) {
                synchronized(this) {
                    val current = outbox[conversationID]?.firstOrNull { it.messageID == entry.messageID }
                    if (current != null) {
                        current.bridgeDeposited = true
                        persistOutbox()
                    }
                }
            }
            if (submitted) {
                entry.lastBridgeAttemptAtMs = clock()
                persistOutbox()
            }
        }
        if (entry.depositedCourierKeys.size >= MAX_COURIERS_PER_MESSAGE) return
        val candidates = mesh.getPeerInfos()
            .asSequence()
            .filter { it.isConnected && it.noisePublicKey != null && !it.noisePublicKey!!.contentEquals(recipientKey) }
            .filter { peer ->
                val favorite = try {
                    com.bitchat.android.favorites.FavoritesPersistenceService.shared
                        .getFavoriteStatus(peer.noisePublicKey!!)?.isMutual == true
                } catch (_: Exception) { false }
                favorite || peer.hasVerifiedAnnouncement
            }
            .map { peer ->
                val favorite = try {
                    com.bitchat.android.favorites.FavoritesPersistenceService.shared
                        .getFavoriteStatus(peer.noisePublicKey!!)?.isMutual == true
                } catch (_: Exception) { false }
                peer to favorite
            }
            .filter { (peer, _) -> ContactIdentityResolver.noiseKeyHex(peer.noisePublicKey!!) !in entry.depositedCourierKeys }
            .sortedByDescending { (_, favorite) -> favorite }
            .take(MAX_COURIERS_PER_MESSAGE - entry.depositedCourierKeys.size)
            .map { (peer, _) -> peer }
            .toList()
        if (candidates.isEmpty()) return
        val accepted = mesh.sendCourierMessage(
            entry.content,
            entry.messageID,
            recipientKey,
            candidates.map { it.id }
        ).toSet()
        candidates.filter { it.id in accepted }.forEach {
            entry.depositedCourierKeys += ContactIdentityResolver.noiseKeyHex(it.noisePublicKey!!)
        }
        if (accepted.isNotEmpty()) persistOutbox()
    }

    private fun canDeliverViaNostrPromptly(): Boolean = try {
        nostr.canDeliverPromptly()
    } catch (_: Exception) { false }

    private fun persistOutbox() {
        try { outboxStore.save(outbox) } catch (e: Exception) {
            Log.e(TAG, "Failed to persist sealed outbox: ${e.message}")
        }
    }

    private fun notifyExpired(messageID: String) {
        try { onMessageExpired?.invoke(messageID) } catch (_: Exception) { }
    }

    /**
     * Initiate a Noise handshake for a conversation with queued messages, applying
     * exponential backoff between attempts. [immediate] resets the backoff (peer just
     * appeared or a new message was queued). Kicks are suppressed while a previous
     * attempt is still inside its backoff window, so alias duplicates and frequent
     * peer-list updates cannot spam handshakes.
     */
    @Synchronized
    private fun kickHandshake(conversationID: String, meshTarget: String, immediate: Boolean) {
        val now = clock()
        val current = retryState[conversationID]
        if (current != null && now < current.nextHandshakeAttemptAtMs) return
        val attempts = if (immediate) 0 else (current?.handshakeAttempts ?: 0)
        try { mesh.initiateNoiseHandshake(meshTarget) } catch (_: Exception) { }
        val backoff = HANDSHAKE_RETRY_BACKOFF_MS[attempts.coerceAtMost(HANDSHAKE_RETRY_BACKOFF_MS.size - 1)]
        retryState[conversationID] = ConversationRetry(
            handshakeAttempts = attempts + 1,
            nextHandshakeAttemptAtMs = now + backoff
        )
        Log.d(TAG, "Handshake attempt ${attempts + 1} for ${conversationID.take(16)}…, next retry in ${backoff}ms")
    }

    @Synchronized
    private fun startOutboxScheduler() {
        if (disableSchedulerForTesting) return
        if (schedulerJob?.isActive == true) return
        schedulerJob = schedulerScope.launch {
            while (isActive) {
                delay(OUTBOX_TICK_MS)
                try { tickOutbox() } catch (e: Exception) {
                    Log.w(TAG, "Outbox scheduler tick failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Stop retrying while the mesh transports are down. Persistent network work must
     * follow the MeshForegroundService lifecycle; getInstance restarts the scheduler
     * and rebinds the mesh reference when the service comes back.
     */
    @Synchronized
    fun stopOutboxScheduler() {
        schedulerJob?.cancel()
        schedulerJob = null
    }

    internal val isSchedulerRunning: Boolean get() = schedulerJob?.isActive == true

    /**
     * One scheduler pass over the outbox: expire old entries, flush what can be sent,
     * and re-initiate handshakes (with backoff) for peers that are connected but have
     * no established session yet.
     */
    @Synchronized
    internal fun tickOutbox(nowMs: Long = clock()) {
        outbox.keys.toList().forEach { conversationID ->
            expireOldEntries(conversationID, nowMs)
            val queued = outbox[conversationID] ?: return@forEach
            if (queued.isEmpty()) return@forEach
            queued.forEach { attemptCourierDeposit(conversationID, it) }

            val resolution = ContactDirectory.resolve(conversationID)
            val meshTarget = resolution.meshPeerID

            if (meshTarget != null && isReady(mesh, meshTarget)) {
                flushOutboxFor(conversationID)
                return@forEach
            }
            if (canSendViaNostr(resolution.noiseKeyHex ?: conversationID)) {
                flushOutboxFor(conversationID)
                return@forEach
            }
            // Peer visible but no session: retry the handshake with backoff.
            if (meshTarget != null && isConnected(mesh, meshTarget)) {
                kickHandshake(conversationID, meshTarget, immediate = false)
            }
        }
    }

    private fun expireOldEntries(conversationID: String, nowMs: Long) {
        val queued = outbox[conversationID] ?: return
        val iterator = queued.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowMs - entry.enqueuedAtMs > OUTBOX_MESSAGE_TTL_MS) {
                Log.w(TAG, "Expiring queued PM for ${conversationID.take(16)}… msg_id=${entry.messageID.take(8)}…")
                iterator.remove()
                notifyExpired(entry.messageID)
            }
        }
        if (queued.isEmpty()) {
            outbox.remove(conversationID, queued)
            retryState.remove(conversationID)
        }
        persistOutbox()
    }

    private fun canSendViaNostr(peerID: String): Boolean {
        return try {
            val resolution = ContactDirectory.resolve(peerID)
            if (resolution.isMutualFavorite && resolution.nostrPubkey != null) return true
            val target = resolution.noiseKeyHex ?: peerID
            if (ContactIdentityResolver.isNoiseKeyHex(target)) {
                val noiseKey = ContactIdentityResolver.bytesFromHex(target) ?: return false
                val fav = com.bitchat.android.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(noiseKey)
                fav?.isMutual == true && fav.peerNostrPublicKey != null
            } else if (ContactIdentityResolver.isMeshPeerId(target)) {
                val fav = com.bitchat.android.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(target)
                fav?.isMutual == true && fav.peerNostrPublicKey != null
            } else {
                false
            }
        } catch (_: Exception) { false }
    }

    private fun isConnected(service: MeshService, peerID: String): Boolean {
        return try {
            service.getPeerInfo(peerID)?.isConnected == true
        } catch (_: Exception) {
            false
        }
    }

    private fun isReady(service: MeshService, peerID: String): Boolean {
        return try {
            service.getPeerInfo(peerID)?.isConnected == true &&
                service.hasEstablishedSession(peerID)
        } catch (_: Exception) {
            false
        }
    }

    // Called when mesh peer list changes; attempt to flush any matching outbox entries
    fun onPeersUpdated(peers: List<String>) {
        peers.forEach { pid ->
            kickHandshakeIfPending(pid)
            flushOutboxFor(pid)
            val noiseHex = try {
                mesh.getPeerInfo(pid)?.noisePublicKey?.let { ContactIdentityResolver.noiseKeyHex(it) }
            } catch (_: Exception) { null }
            noiseHex?.let {
                kickHandshakeIfPending(it)
                if (ContactDirectory.canonicalConversationId(it) != ContactDirectory.canonicalConversationId(pid)) {
                    flushOutboxFor(it)
                }
            }
            retryCourierDeposits()
        }
    }

    @Synchronized
    private fun retryCourierDeposits() {
        outbox.forEach { (conversationID, entries) ->
            entries.forEach { attemptCourierDeposit(conversationID, it) }
        }
    }

    // Called when a Noise session becomes established; flush both the mesh peerID and its noiseHex alias
    fun onSessionEstablished(peerID: String) {
        resetRetry(peerID)
        flushOutboxFor(peerID)
        val noiseHex = try {
            mesh.getPeerInfo(peerID)?.noisePublicKey?.let { ContactIdentityResolver.noiseKeyHex(it) }
        } catch (_: Exception) { null }
        noiseHex?.let {
            resetRetry(it)
            if (ContactDirectory.canonicalConversationId(it) != ContactDirectory.canonicalConversationId(peerID)) {
                flushOutboxFor(it)
            }
        }
    }

    /** Reset handshake backoff for a conversation whose session just came up. */
    private fun resetRetry(peerID: String) {
        retryState.remove(ContactDirectory.canonicalConversationId(peerID))
        retryState.remove(peerID)
    }

    /**
     * A peer (re)appeared: if we still owe them queued messages and there is no working
     * session yet, restart the handshake immediately instead of waiting for the backoff.
     */
    @Synchronized
    private fun kickHandshakeIfPending(peerID: String) {
        val conversationID = ContactDirectory.canonicalConversationId(peerID)
        val queued = outbox[conversationID] ?: outbox[peerID] ?: return
        if (queued.isEmpty()) return
        val resolution = ContactDirectory.resolve(conversationID)
        val meshTarget = resolution.meshPeerID ?: return
        if (isReady(mesh, meshTarget)) return
        if (!isConnected(mesh, meshTarget)) return
        Log.d(TAG, "Peer ${meshTarget.take(8)}… reappeared with ${queued.size} queued PM(s); re-initiating handshake")
        kickHandshake(conversationID, meshTarget, immediate = true)
    }
}
