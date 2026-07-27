package com.bitchat.android.services.bridge

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.CourierEnvelope
import com.bitchat.android.model.NoisePayload
import com.bitchat.android.model.NoisePayloadType
import com.bitchat.android.model.PrivateMessagePacket
import com.bitchat.android.nostr.NostrEvent
import com.bitchat.android.nostr.NostrIdentity
import com.bitchat.android.nostr.NostrKind
import com.bitchat.android.nostr.NostrProtocol
import com.bitchat.android.nostr.NostrPublishResult
import com.bitchat.android.nostr.NostrRelayManager
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.ContactIdentityResolver
import com.bitchat.android.services.MessageRouter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Date

/**
 * Store-and-forward courier state machine.
 *
 * All mutable state and persistent dedup access are confined to [dispatcher].
 * The bridge facade only supplies immutable peer snapshots and transport
 * dependencies.
 */
internal class CourierCoordinator(
    context: Context,
    preferences: SharedPreferences,
    private val relayManager: NostrRelayManager,
    private val prekeys: PrekeyManager,
    private val meshProvider: () -> MeshService?,
    private val peersProvider: () -> List<VerifiedBridgePeer>,
    private val isTrustedDepositor: (VerifiedBridgePeer) -> Boolean,
    private val isSenderBlocked: (ByteArray) -> Boolean,
    private val onPrekeyConsumed: suspend () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
) {
    private data class PendingDrop(
        val envelope: CourierEnvelope,
        val dedupKey: String?,
        val queueKey: String,
        val depositorKey: String? = null,
        val attemptedGatewayPeerIds: MutableSet<String> = mutableSetOf()
    )

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val pendingDrops = mutableListOf<PendingDrop>()
    private val signatureAttemptTimes = mutableListOf<Long>()
    private val depositTimesByPeer = mutableMapOf<String, MutableList<Long>>()
    private var subscribedTags: Set<String> = emptySet()
    private val courierSubscription = RelaySubscriptionSlot("mesh-bridge-courier")
    @Volatile
    private var enabled = false
    private val publishedDropKeys =
        PersistentExpiringIdSet(preferences, "published_drop_keys", MAX_TRACKED_IDS)
    private val seenDropEventIds =
        PersistentExpiringIdSet(preferences, "seen_drop_events", MAX_TRACKED_IDS)
    private val openedMessageIds =
        PersistentExpiringIdSet(preferences, "opened_courier_messages", MAX_TRACKED_IDS)

    fun setEnabled(value: Boolean) {
        // Privacy policy changes take effect before queued coordinator work.
        enabled = value
        scope.launch {
            if (value) {
                refreshSubscription()
            } else {
                closeSubscription()
                pendingDrops.clear()
            }
        }
    }

    fun peerStateChanged() {
        scope.launch {
            if (!enabled) return@launch
            refreshSubscription()
            retryPendingGatewayHandoffs()
        }
    }

    fun relayConnected() {
        scope.launch {
            if (!enabled) return@launch
            refreshSubscription()
            flushPendingDrops()
        }
    }

    fun handleEnvelope(packet: BitchatPacket, fromPeerId: String, directIngress: Boolean) {
        scope.launch {
            val envelope = CourierEnvelope.decode(packet.payload) ?: return@launch
            if (!validLifetime(envelope)) return@launch
            if (isMyTag(envelope.recipientTag)) {
                openEnvelope(envelope)
                return@launch
            }
            if (!enabled) return@launch

            if (!allowSignatureAttempt()) return@launch
            val depositor = authenticatedDepositor(packet, fromPeerId, directIngress)
                ?: return@launch
            if (!allowDeposit(depositor.peerId)) return@launch
            publishOrQueue(
                envelope = envelope,
                dedupKey = null,
                depositorKey = depositor.peerId
            )
        }
    }

    suspend fun deposit(
        content: String,
        messageId: String,
        recipientNoiseKey: ByteArray
    ): CourierDepositResult = withContext(dispatcher) {
        if (!enabled) {
            return@withContext CourierDepositResult.Rejected(
                CourierDepositResult.Reason.BRIDGE_DISABLED
            )
        }
        if (content.toByteArray(Charsets.UTF_8).size > MAX_PRIVATE_MESSAGE_BYTES) {
            return@withContext CourierDepositResult.Rejected(
                CourierDepositResult.Reason.CONTENT_TOO_LARGE
            )
        }
        val now = clock()
        val dedupKey = senderDropKey(messageId, recipientNoiseKey)
        if (publishedDropKeys.contains(dedupKey, now)) {
            return@withContext CourierDepositResult.AlreadyPublished
        }
        if (pendingDrops.any { it.queueKey == dedupKey }) {
            return@withContext CourierDepositResult.QueuedLocally
        }
        val privatePacket = PrivateMessagePacket(messageId, content).encode()
            ?: return@withContext CourierDepositResult.Rejected(
                CourierDepositResult.Reason.INVALID_MESSAGE
            )
        val typedPayload = NoisePayload(NoisePayloadType.PRIVATE_MESSAGE, privatePacket).encode()
        val livePeer = peersProvider().firstOrNull {
            it.noiseKey.contentEquals(recipientNoiseKey) &&
                meshProvider()?.getPeerInfo(it.peerId)?.isConnected == true
        }
        val allowsPrekeys = livePeer?.capabilities?.contains(
            com.bitchat.android.model.PeerCapabilities.PREKEYS
        ) != false
        val sealed = runCatching {
            prekeys.seal(
                typedPayload,
                messageId,
                recipientNoiseKey,
                recipientAdvertisesPrekeys = allowsPrekeys,
                nowMs = clock()
            )
        }.getOrNull() ?: return@withContext CourierDepositResult.Rejected(
            CourierDepositResult.Reason.ENCRYPTION_FAILED
        )
        val envelope = CourierEnvelope(
            recipientTag = CourierEnvelope.recipientTag(
                recipientNoiseKey,
                CourierEnvelope.epochDay(now)
            ),
            expiry = now + CourierEnvelope.MAX_LIFETIME_MS,
            ciphertext = sealed.ciphertext,
            copies = 1,
            prekeyId = sealed.prekeyId
        )
        val encoded = envelope.encode()
            ?: return@withContext CourierDepositResult.Rejected(
                CourierDepositResult.Reason.INVALID_MESSAGE
            )
        if (encoded.size > MAX_DROP_BYTES) {
            return@withContext CourierDepositResult.Rejected(
                CourierDepositResult.Reason.CONTENT_TOO_LARGE
            )
        }
        if (relayManager.isConnected.value) {
            return@withContext publishOrQueue(envelope, dedupKey)
        }
        val gateway = availableGateway()
        if (gateway != null) {
            meshProvider()?.sendCourierEnvelope(encoded, gateway.peerId)
            // Keep a local copy until a relay accepts it. If the gateway is
            // lost before publishing, router retries can choose another path.
            enqueue(
                PendingDrop(
                    envelope,
                    dedupKey,
                    dedupKey,
                    attemptedGatewayPeerIds = mutableSetOf(gateway.peerId)
                )
            )
            return@withContext CourierDepositResult.ForwardedToGateway
        }
        enqueue(PendingDrop(envelope, dedupKey, dedupKey))
        CourierDepositResult.QueuedLocally
    }

    suspend fun wipe() = withContext(dispatcher) {
        closeSubscription()
        pendingDrops.clear()
        signatureAttemptTimes.clear()
        depositTimesByPeer.clear()
        publishedDropKeys.clear()
        seenDropEventIds.clear()
        openedMessageIds.clear()
    }

    private fun refreshSubscription() {
        if (!enabled) return
        val now = clock()
        val identityKey =
            SecureIdentityStateManager(appContext).loadStaticKey()?.second ?: return
        val myTags = CourierEnvelope.candidateTags(identityKey, now).map { it.toHex() }.toSet()
        val peerTags = peersProvider()
            .asSequence()
            .filter { meshProvider()?.getPeerInfo(it.peerId)?.isConnected == true }
            .take(MAX_WATCHED_PEERS)
            .flatMap { peer ->
                CourierEnvelope.candidateTags(peer.noiseKey, now)
                    .asSequence()
                    .map { bytes -> bytes.toHex() }
            }
            .toSet()
        val allTags = myTags + peerTags
        if (allTags == subscribedTags) return
        if (allTags.isEmpty()) {
            courierSubscription.close(relayManager::unsubscribe)
            subscribedTags = emptySet()
        } else {
            courierSubscription.replace(
                close = relayManager::unsubscribe,
                open = { subscriptionId ->
                    relayManager.subscribe(
                        filter = com.bitchat.android.nostr.NostrFilter.courierDrops(
                            allTags,
                            since = now - CourierEnvelope.MAX_LIFETIME_MS
                        ),
                        id = subscriptionId,
                        handler = { event -> scope.launch { handleDropEvent(event) } },
                        targetRelayUrls = NostrRelayManager.defaultRelays()
                    )
                }
            )
            subscribedTags = allTags
        }
    }

    private suspend fun handleDropEvent(event: NostrEvent) {
        if (!enabled ||
            event.kind != NostrKind.COURIER_DROP ||
            seenDropEventIds.contains(event.id, clock()) ||
            !allowSignatureAttempt() ||
            !event.isValidSignature()
        ) {
            return
        }
        val data = runCatching { Base64.decode(event.content, Base64.DEFAULT) }.getOrNull() ?: return
        if (data.size > MAX_DROP_BYTES) return
        val envelope = CourierEnvelope.decode(data) ?: return
        if (!validLifetime(envelope)) return
        val tagHex = envelope.recipientTag.toHex()
        if (event.tags.none { it.size >= 2 && it[0] == "x" && it[1] == tagHex }) return

        if (isMyTag(envelope.recipientTag)) {
            if (openEnvelope(envelope)) {
                seenDropEventIds.add(event.id, DROP_DEDUP_MS, clock())
            }
            return
        }
        val peer = peersProvider()
            .asSequence()
            .filter { meshProvider()?.getPeerInfo(it.peerId)?.isConnected == true }
            .take(MAX_WATCHED_PEERS)
            .firstOrNull {
                CourierEnvelope.candidateTags(it.noiseKey, clock())
                    .any { candidate -> candidate.contentEquals(envelope.recipientTag) }
            }
        if (peer != null) {
            meshProvider()?.sendCourierEnvelope(data, peer.peerId)
            seenDropEventIds.add(event.id, DROP_DEDUP_MS, clock())
        }
    }

    private suspend fun openEnvelope(envelope: CourierEnvelope): Boolean {
        val opened = runCatching {
            prekeys.open(envelope.ciphertext, envelope.prekeyId, clock())
        }.getOrNull() ?: return false
        // Consumption happened during open. Announce the reduced/replenished
        // bundle before any later payload parser can return early.
        if (opened.consumedPrekey) onPrekeyConsumed()
        val payload = NoisePayload.decode(opened.payload) ?: return true
        if (payload.type != NoisePayloadType.PRIVATE_MESSAGE) return true
        val privateMessage = PrivateMessagePacket.decode(payload.data) ?: return true
        if (openedMessageIds.contains(privateMessage.messageID, clock())) return true
        openedMessageIds.add(privateMessage.messageID, DROP_DEDUP_MS, clock())
        if (isSenderBlocked(opened.senderStaticKey)) return true
        val senderPeerId = ContactIdentityResolver.peerIdForNoiseKey(opened.senderStaticKey)
        val senderResolution = ContactDirectory.resolve(opened.senderStaticKey.toHex())
        val message = BitchatMessage(
            id = privateMessage.messageID,
            sender = senderResolution.displayName
                ?: peersProvider().firstOrNull { it.peerId == senderPeerId }?.nickname
                ?: "Unknown",
            content = privateMessage.content,
            timestamp = Date(clock()),
            isPrivate = true,
            recipientNickname = meshProvider()?.myPeerID,
            senderPeerID = senderPeerId
        )
        val delivered = AppStateStore.addPrivateMessage(
            ContactIdentityResolver.contactConversationIdForNoiseKey(opened.senderStaticKey),
            message
        )
        if (!delivered) return true
        CourierMessagePort.deliver(message)
        MessageRouter.tryGetInstance()?.sendDeliveryAck(
            privateMessage.messageID,
            opened.senderStaticKey.toHex()
        )
        return true
    }

    private suspend fun publishOrQueue(
        envelope: CourierEnvelope,
        dedupKey: String?,
        depositorKey: String? = null
    ): CourierDepositResult {
        if (!validLifetime(envelope)) {
            return CourierDepositResult.Rejected(CourierDepositResult.Reason.INVALID_MESSAGE)
        }
        if (!relayManager.isConnected.value) {
            enqueue(
                PendingDrop(
                    envelope,
                    dedupKey,
                    dedupKey ?: envelopeQueueKey(envelope),
                    depositorKey
                )
            )
            return CourierDepositResult.QueuedLocally
        }
        val encoded = envelope.encode()
            ?: return CourierDepositResult.Rejected(CourierDepositResult.Reason.INVALID_MESSAGE)
        if (encoded.size > MAX_DROP_BYTES) {
            return CourierDepositResult.Rejected(CourierDepositResult.Reason.CONTENT_TOO_LARGE)
        }
        val event = NostrProtocol.createCourierDropEvent(
            envelope = encoded,
            recipientTagHex = envelope.recipientTag.toHex(),
            expiresAtMs = envelope.expiry,
            senderIdentity = NostrIdentity.generate()
        )
        return when (
            relayManager.sendEventAndAwaitAcceptance(
                event,
                NostrRelayManager.defaultRelays()
            )
        ) {
            is NostrPublishResult.Accepted -> {
                if (!enabled) {
                    return CourierDepositResult.Rejected(
                        CourierDepositResult.Reason.BRIDGE_DISABLED
                    )
                }
                dedupKey?.let { publishedDropKeys.add(it, DROP_DEDUP_MS, clock()) }
                CourierDepositResult.Published
            }
            is NostrPublishResult.Rejected,
            NostrPublishResult.TimedOut -> {
                if (!enabled) {
                    return CourierDepositResult.Rejected(
                        CourierDepositResult.Reason.BRIDGE_DISABLED
                    )
                }
                enqueue(
                    PendingDrop(
                        envelope,
                        dedupKey,
                        dedupKey ?: envelopeQueueKey(envelope),
                        depositorKey
                    )
                )
                CourierDepositResult.QueuedLocally
            }
        }
    }

    private fun enqueue(drop: PendingDrop) {
        pendingDrops.firstOrNull { it.queueKey == drop.queueKey }?.let { existing ->
            existing.attemptedGatewayPeerIds += drop.attemptedGatewayPeerIds
            return
        }
        if (drop.depositorKey != null &&
            pendingDrops.count { it.depositorKey == drop.depositorKey } >=
            MAX_PENDING_DROPS_PER_DEPOSITOR
        ) {
            return
        }
        pendingDrops += drop
        while (pendingDrops.size > MAX_PENDING_DROPS) pendingDrops.removeAt(0)
    }

    private fun retryPendingGatewayHandoffs() {
        if (relayManager.isConnected.value) return
        pendingDrops.forEach { drop ->
            if (drop.depositorKey != null) return@forEach
            if (drop.attemptedGatewayPeerIds.size >= MAX_GATEWAYS_PER_DROP) return@forEach
            val gateway = availableGateway(excluding = drop.attemptedGatewayPeerIds)
                ?: return@forEach
            val encoded = drop.envelope.encode() ?: return@forEach
            meshProvider()?.sendCourierEnvelope(encoded, gateway.peerId)
            drop.attemptedGatewayPeerIds += gateway.peerId
        }
    }

    private suspend fun flushPendingDrops() {
        if (!enabled || !relayManager.isConnected.value) return
        val queued = pendingDrops.toList()
        pendingDrops.clear()
        queued.forEach { publishOrQueue(it.envelope, it.dedupKey, it.depositorKey) }
    }

    private fun closeSubscription() {
        courierSubscription.close(relayManager::unsubscribe)
        subscribedTags = emptySet()
    }

    private fun availableGateway(excluding: Set<String> = emptySet()): VerifiedBridgePeer? =
        peersProvider().firstOrNull { peer ->
            peer.peerId !in excluding &&
            peer.capabilities?.contains(com.bitchat.android.model.PeerCapabilities.BRIDGE) == true &&
                peer.bridgeCell != null &&
                meshProvider()?.getPeerInfo(peer.peerId)?.let { info ->
                    info.isConnected && info.isDirectConnection
                } == true &&
                isTrustedDepositor(peer)
        }

    private fun isMyTag(tag: ByteArray): Boolean {
        val ownKey = SecureIdentityStateManager(appContext).loadStaticKey()?.second ?: return false
        return CourierEnvelope.candidateTags(ownKey, clock()).any { it.contentEquals(tag) }
    }

    private fun authenticatedDepositor(
        packet: BitchatPacket,
        fromPeerId: String,
        directIngress: Boolean
    ): VerifiedBridgePeer? = CourierDepositAuthenticator.authenticate(
        packet = packet,
        fromPeerId = fromPeerId,
        directIngress = directIngress,
        peers = peersProvider(),
        isTrusted = isTrustedDepositor
    )

    private fun allowDeposit(peerId: String): Boolean {
        val now = clock()
        val attempts = depositTimesByPeer.getOrPut(peerId) { mutableListOf() }
        attempts.removeAll { now - it >= RATE_WINDOW_MS }
        if (attempts.size >= DEPOSITS_PER_MINUTE_PER_PEER) return false
        attempts += now
        return true
    }

    private fun validLifetime(envelope: CourierEnvelope): Boolean {
        val now = clock()
        return !envelope.isExpired(now) &&
            envelope.expiry > 0 &&
            envelope.expiry - now <= CourierEnvelope.MAX_LIFETIME_MS
    }

    private fun senderDropKey(messageId: String, recipientNoiseKey: ByteArray): String {
        val material = recipientNoiseKey.toHex() + "|" + messageId
        return MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
            .toHex()
    }

    private fun envelopeQueueKey(envelope: CourierEnvelope): String =
        MessageDigest.getInstance("SHA-256")
            .digest(envelope.recipientTag + envelope.ciphertext)
            .toHex()

    private fun allowSignatureAttempt(): Boolean {
        val now = clock()
        signatureAttemptTimes.removeAll { now - it >= RATE_WINDOW_MS }
        if (signatureAttemptTimes.size >= SIGNATURE_ATTEMPTS_PER_MINUTE) return false
        signatureAttemptTimes += now
        return true
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_TRACKED_IDS = 512
        const val MAX_WATCHED_PEERS = 16
        const val MAX_PENDING_DROPS = 20
        const val MAX_PENDING_DROPS_PER_DEPOSITOR = 5
        const val MAX_GATEWAYS_PER_DROP = 2
        const val MAX_DROP_BYTES = 20 * 1024
        const val MAX_PRIVATE_MESSAGE_BYTES = 255
        const val DROP_DEDUP_MS = 24L * 60 * 60 * 1000
        const val RATE_WINDOW_MS = 60_000L
        const val SIGNATURE_ATTEMPTS_PER_MINUTE = 720
        const val DEPOSITS_PER_MINUTE_PER_PEER = 10
    }
}
