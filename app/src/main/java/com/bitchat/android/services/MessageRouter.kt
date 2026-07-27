package com.bitchat.android.services

import android.content.Context
import android.util.Log
import com.bitchat.android.favorites.FavoriteControlMessage
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.model.ReadReceipt
import com.bitchat.android.nostr.NostrRelayManager
import com.bitchat.android.nostr.NostrTransport
import com.bitchat.android.services.bridge.CourierDepositResult
import com.bitchat.android.services.bridge.MeshBridgeService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Routes messages between local mesh transports and Nostr, matching iOS behavior.
 */
class MessageRouter private constructor(
    private var mesh: MeshService,
    private val nostr: NostrTransport,
    private val relayManager: NostrRelayManager,
    private val currentNostrIdentity: () -> com.bitchat.android.nostr.NostrIdentity?
) {
    private data class OutboxMessage(
        val content: String,
        val recipientNickname: String,
        val messageId: String,
        val createdAtMs: Long = System.currentTimeMillis(),
        var lastMeshAttemptMs: Long = 0,
        var lastNostrAttemptMs: Long = 0
    )

    private data class OutboxKey(
        val conversationID: String,
        val messageID: String
    )

    enum class RouteResult {
        MESH,
        NOSTR,
        QUEUED,
        DROPPED
    }

    companion object {
        private const val TAG = "MessageRouter"
        private const val RETRY_INTERVAL_MS = 60_000L
        private const val TRANSPORT_RETRY_INTERVAL_MS = 5L * 60 * 1000
        private const val OUTBOX_TTL_MS = 48L * 60 * 60 * 1000
        private const val MAX_OUTBOX_PER_CONVERSATION = 50
        @Volatile private var INSTANCE: MessageRouter? = null
        fun tryGetInstance(): MessageRouter? = INSTANCE
        fun getInstance(context: Context, mesh: MeshService): MessageRouter {
            val instance = INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val application = context.applicationContext
                    val nostr = NostrTransport.getInstance(application)
                    MessageRouter(
                        mesh = mesh,
                        nostr = nostr,
                        relayManager = NostrRelayManager.getInstance(application),
                        currentNostrIdentity = {
                            com.bitchat.android.nostr.NostrIdentityBridge
                                .getCurrentNostrIdentity(application)
                        }
                    ).also { instance ->
                        // Register for favorites changes to flush outbox
                        try {
                            com.bitchat.android.favorites.FavoritesPersistenceService.shared.addListener(instance.favoriteListener)
                        } catch (_: Exception) {}
                        INSTANCE = instance
                    }
                }
            }
            // Always update mesh reference and sync peer ID
            instance.mesh = mesh
            instance.nostr.senderPeerID = mesh.myPeerID
            return instance
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val outboxLock = Any()
    private val outbox = mutableMapOf<String, MutableList<OutboxMessage>>()
    private val courierAttemptTimes = mutableMapOf<OutboxKey, Long>()
    private val courierAttemptsInFlight = mutableSetOf<OutboxKey>()

    init {
        scope.launch {
            MeshBridgeService.isEnabled.collect { enabled ->
                if (enabled) retryCourierDeposits(force = true)
            }
        }
        scope.launch {
            relayManager.isConnected.collect { connected ->
                if (connected) flushAllOutbox()
            }
        }
        scope.launch {
            while (isActive) {
                delay(RETRY_INTERVAL_MS)
                pruneExpiredOutbox()
                retryCourierDeposits(force = false)
                if (relayManager.isConnected.value) flushAllOutbox()
            }
        }
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
        } else if (canSendViaNostr(nostrTarget) && relayManager.isConnected.value) {
            Log.d(TAG, "Routing PM via Nostr to ${conversationID.take(32)}… msg_id=${messageID.take(8)}…")
            nostr.sendPrivateMessage(content, nostrTarget, recipientNickname, messageID)
            return RouteResult.NOSTR
        } else {
            Log.d(TAG, "Queued PM for ${conversationID.take(16)}… (no ready transport) msg_id=${messageID.take(8)}…")
            enqueueOutbox(
                conversationID,
                OutboxMessage(content, recipientNickname, messageID)
            )
            resolution.noisePublicKey?.let { recipientNoiseKey ->
                attemptCourierDeposit(
                    conversationID,
                    OutboxMessage(content, recipientNickname, messageID),
                    recipientNoiseKey,
                    force = true
                )
            }
            Log.d(TAG, "Initiating noise handshake after queueing PM for ${conversationID.take(16)}…")
            if (hasMesh) mesh.initiateNoiseHandshake(meshTarget)
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
                nostr.sendDeliveryAckGeohash(
                    messageID,
                    recipientHex,
                    try {
                        currentNostrIdentity() ?: return
                    } catch (_: Exception) {
                        return
                    }
                )
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
            val myNpub = try {
                currentNostrIdentity()?.npub
            } catch (_: Exception) {
                null
            }
            val content = FavoriteControlMessage.encode(isFavorite, myNpub)
            val nickname = mesh.getPeerNicknames()[meshTarget] ?: meshTarget
            mesh.sendPrivateMessage(content, meshTarget, nickname, null)
        } else {
            nostr.sendFavoriteNotification(resolution.noiseKeyHex ?: toPeerID, isFavorite)
        }
    }

    // Flush any queued messages for a specific peerID
    fun flushOutboxFor(peerID: String) {
        val conversationID = ContactDirectory.canonicalConversationId(peerID)
        val matchingQueues = synchronized(outboxLock) {
            outbox.entries
                .filter { (key, _) ->
                    key == peerID ||
                        ContactDirectory.canonicalConversationId(key) == conversationID
                }
                .map { it.key to it.value.toList() }
        }
        if (matchingQueues.isEmpty()) return
        Log.d(
            TAG,
            "Flushing outbox for ${conversationID.take(16)}… count=${matchingQueues.sumOf { it.second.size }}"
        )
        matchingQueues.forEach { (queuedConversationID, queued) ->
            queued.forEach messageLoop@{ queuedMessage ->
                val resolution = ContactDirectory.resolve(queuedConversationID)
                val meshTarget = resolution.meshPeerID
                val nostrTarget = resolution.noiseKeyHex ?: queuedConversationID
                if (meshTarget != null && isReady(mesh, meshTarget)) {
                    if (!shouldAttemptTransport(queuedMessage, mesh = true)) return@messageLoop
                    mesh.sendPrivateMessage(
                        queuedMessage.content,
                        meshTarget,
                        queuedMessage.recipientNickname,
                        queuedMessage.messageId
                    )
                    recordTransportAttempt(
                        queuedConversationID,
                        queuedMessage.messageId,
                        mesh = true
                    )
                } else if (canSendViaNostr(nostrTarget) && relayManager.isConnected.value) {
                    if (!shouldAttemptTransport(queuedMessage, mesh = false)) return@messageLoop
                    nostr.sendPrivateMessage(
                        queuedMessage.content,
                        nostrTarget,
                        queuedMessage.recipientNickname,
                        queuedMessage.messageId
                    )
                    recordTransportAttempt(
                        queuedConversationID,
                        queuedMessage.messageId,
                        mesh = false
                    )
                }
            }
        }
    }

    // Flush everything (rarely used)
    fun flushAllOutbox() {
        synchronized(outboxLock) { outbox.keys.toList() }.forEach { flushOutboxFor(it) }
    }

    /** Stop retrying a retained message once any transport confirms delivery. */
    fun acknowledge(messageID: String, fromPeerID: String) {
        val acknowledgedConversation = ContactDirectory.canonicalConversationId(fromPeerID)
        synchronized(outboxLock) {
            outbox.entries.forEach { (conversationID, messages) ->
                if (ContactDirectory.canonicalConversationId(conversationID) ==
                    acknowledgedConversation
                ) {
                    messages.removeAll { it.messageId == messageID }
                    val key = OutboxKey(conversationID, messageID)
                    courierAttemptTimes.remove(key)
                    courierAttemptsInFlight.remove(key)
                }
            }
            outbox.entries.removeAll { it.value.isEmpty() }
        }
    }

    /** Panic-mode hook: retained plaintext and retry metadata must not survive. */
    fun wipeOutbox() {
        synchronized(outboxLock) {
            outbox.clear()
            courierAttemptTimes.clear()
            courierAttemptsInFlight.clear()
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
            flushOutboxFor(pid)
            val noiseHex = try {
                mesh.getPeerInfo(pid)?.noisePublicKey?.let { ContactIdentityResolver.noiseKeyHex(it) }
            } catch (_: Exception) { null }
            noiseHex?.let { flushOutboxFor(it) }
        }
        retryCourierDeposits(force = true)
    }

    // Called when a Noise session becomes established; flush both the mesh peerID and its noiseHex alias
    fun onSessionEstablished(peerID: String) {
        flushOutboxFor(peerID)
        val noiseHex = try {
            mesh.getPeerInfo(peerID)?.noisePublicKey?.let { ContactIdentityResolver.noiseKeyHex(it) }
        } catch (_: Exception) { null }
        noiseHex?.let { flushOutboxFor(it) }
    }

    private fun enqueueOutbox(conversationID: String, message: OutboxMessage) {
        synchronized(outboxLock) {
            val queue = outbox.getOrPut(conversationID) { mutableListOf() }
            if (queue.any { it.messageId == message.messageId }) return
            queue += message
            while (queue.size > MAX_OUTBOX_PER_CONVERSATION) {
                val removed = queue.removeAt(0)
                val key = OutboxKey(conversationID, removed.messageId)
                courierAttemptTimes.remove(key)
                courierAttemptsInFlight.remove(key)
                AppStateStore.updatePrivateMessageStatus(
                    removed.messageId,
                    com.bitchat.android.model.DeliveryStatus.Failed("delivery queue full")
                )
            }
        }
    }

    private fun retryCourierDeposits(force: Boolean) {
        val queued = synchronized(outboxLock) {
            outbox.flatMap { (conversationID, messages) ->
                messages.filter { !isExpired(it) }.map { conversationID to it }
            }
        }
        queued.forEach { (conversationID, message) ->
            val recipientNoiseKey =
                ContactDirectory.resolve(conversationID).noisePublicKey
            if (recipientNoiseKey != null) {
                attemptCourierDeposit(conversationID, message, recipientNoiseKey, force)
            }
        }
    }

    private fun attemptCourierDeposit(
        conversationID: String,
        message: OutboxMessage,
        recipientNoiseKey: ByteArray,
        force: Boolean
    ) {
        val now = System.currentTimeMillis()
        val key = OutboxKey(conversationID, message.messageId)
        synchronized(outboxLock) {
            if (key in courierAttemptsInFlight) return
            val lastAttempt = courierAttemptTimes[key] ?: 0L
            if (!force && now - lastAttempt < RETRY_INTERVAL_MS) return
            courierAttemptTimes[key] = now
            courierAttemptsInFlight += key
        }
        scope.launch {
            val result = runCatching {
                MeshBridgeService.depositCourierDrop(
                    content = message.content,
                    messageId = message.messageId,
                    recipientNoiseKey = recipientNoiseKey
                )
            }.getOrElse { error ->
                Log.w(TAG, "Courier deposit failed: ${error.message}")
                null
            }
            synchronized(outboxLock) {
                courierAttemptsInFlight.remove(key)
            }
            if (result is CourierDepositResult.Rejected) {
                Log.d(TAG, "Courier deposit rejected: ${result.reason}")
            } else if (
                result == CourierDepositResult.Published ||
                result == CourierDepositResult.ForwardedToGateway ||
                result == CourierDepositResult.AlreadyPublished
            ) {
                AppStateStore.updatePrivateMessageStatus(
                    message.messageId,
                    com.bitchat.android.model.DeliveryStatus.Sent
                )
            }
        }
    }

    private fun pruneExpiredOutbox() {
        synchronized(outboxLock) {
            outbox.forEach { (conversationID, messages) ->
                val expiredIds = messages.filter(::isExpired).map { it.messageId }.toSet()
                messages.removeAll { it.messageId in expiredIds }
                expiredIds.forEach {
                    val key = OutboxKey(conversationID, it)
                    courierAttemptTimes.remove(key)
                    courierAttemptsInFlight.remove(key)
                    AppStateStore.updatePrivateMessageStatus(
                        it,
                        com.bitchat.android.model.DeliveryStatus.Failed("delivery expired")
                    )
                }
            }
            outbox.entries.removeAll { it.value.isEmpty() }
        }
    }

    private fun shouldAttemptTransport(message: OutboxMessage, mesh: Boolean): Boolean {
        val lastAttempt = synchronized(outboxLock) {
            if (mesh) message.lastMeshAttemptMs else message.lastNostrAttemptMs
        }
        return System.currentTimeMillis() - lastAttempt >= TRANSPORT_RETRY_INTERVAL_MS
    }

    private fun recordTransportAttempt(
        conversationID: String,
        messageID: String,
        mesh: Boolean
    ) {
        val now = System.currentTimeMillis()
        synchronized(outboxLock) {
            outbox[conversationID].orEmpty()
                .filter { it.messageId == messageID }
                .forEach { message ->
                    if (mesh) message.lastMeshAttemptMs = now else message.lastNostrAttemptMs = now
                }
        }
    }

    private fun isExpired(message: OutboxMessage): Boolean =
        System.currentTimeMillis() - message.createdAtMs >= OUTBOX_TTL_MS

}
