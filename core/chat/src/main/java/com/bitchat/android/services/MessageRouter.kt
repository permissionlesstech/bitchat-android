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

    private data class QueuedMessage(
        val content: String,
        val nickname: String,
        val messageID: String,
        val enqueuedAtMs: Long
    )

    private data class ConversationRetry(
        val handshakeAttempts: Int,
        val nextHandshakeAttemptAtMs: Long
    )

    companion object {
        private const val TAG = "MessageRouter"
        private const val OUTBOX_TICK_MS = AppConstants.Router.OUTBOX_TICK_MS
        private const val OUTBOX_MESSAGE_TTL_MS = AppConstants.Router.OUTBOX_MESSAGE_TTL_MS
        private const val OUTBOX_MAX_PER_PEER = AppConstants.Router.OUTBOX_MAX_PER_PEER
        private val HANDSHAKE_RETRY_BACKOFF_MS = AppConstants.Router.HANDSHAKE_RETRY_BACKOFF_MS

        @Volatile private var INSTANCE: MessageRouter? = null
        internal var disableSchedulerForTesting = false
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
    }

    // Outbox: conversationID -> queued messages, oldest first
    private val outbox = ConcurrentHashMap<String, MutableList<QueuedMessage>>()

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

    fun clearAll() {
        outbox.clear()
        retryState.clear()
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
        if (meshTarget != null && isReady(mesh, meshTarget)) {
            Log.d(TAG, "Routing PM via mesh to ${meshTarget} msg_id=${messageID.take(8)}…")
            mesh.sendPrivateMessage(content, meshTarget, recipientNickname, messageID)
            return RouteResult.MESH
        } else if (canSendViaNostr(nostrTarget)) {
            Log.d(TAG, "Routing PM via Nostr to ${conversationID.take(32)}… msg_id=${messageID.take(8)}…")
            nostr.sendPrivateMessage(content, nostrTarget, recipientNickname, messageID)
            return RouteResult.NOSTR
        } else {
            Log.d(TAG, "Queued PM for ${conversationID} (no mesh, no Nostr mapping) msg_id=${messageID.take(8)}…")
            enqueue(conversationID, QueuedMessage(content, recipientNickname, messageID, clock()))
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
            if (meshTarget != null && isReady(mesh, meshTarget)) {
                mesh.sendPrivateMessage(entry.content, meshTarget, entry.nickname, entry.messageID)
                iterator.remove()
            } else if (canSendViaNostr(nostrTarget)) {
                nostr.sendPrivateMessage(entry.content, nostrTarget, entry.nickname, entry.messageID)
                iterator.remove()
            }
        }
        if (queued.isEmpty()) {
            outbox.remove(conversationID, queued)
            outbox.remove(peerID, queued)
            retryState.remove(conversationID)
            retryState.remove(peerID)
        }
    }

    // Flush everything (rarely used)
    fun flushAllOutbox() {
        outbox.keys.toList().forEach { flushOutboxFor(it) }
    }

    @Synchronized
    private fun enqueue(conversationID: String, entry: QueuedMessage) {
        val queue = outbox.getOrPut(conversationID) { mutableListOf() }
        queue.add(entry)
        while (queue.size > OUTBOX_MAX_PER_PEER) {
            val evicted = queue.removeAt(0)
            Log.w(TAG, "Outbox full for ${conversationID.take(16)}…; evicting oldest msg_id=${evicted.messageID.take(8)}…")
            notifyExpired(evicted.messageID)
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
                flushOutboxFor(it)
            }
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
            flushOutboxFor(it)
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
