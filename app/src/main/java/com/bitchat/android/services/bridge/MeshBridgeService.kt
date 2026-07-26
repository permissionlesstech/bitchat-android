package com.bitchat.android.services.bridge

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import com.bitchat.android.geohash.Geohash
import com.bitchat.android.geohash.GeohashChannelLevel
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.CourierEnvelope
import com.bitchat.android.model.IdentityAnnouncement
import com.bitchat.android.model.NoisePayload
import com.bitchat.android.model.NoisePayloadType
import com.bitchat.android.model.NostrCarrierPacket
import com.bitchat.android.model.PeerCapabilities
import com.bitchat.android.model.PrekeyBundle
import com.bitchat.android.model.PrivateMessagePacket
import com.bitchat.android.nostr.MeshMessageIdentity
import com.bitchat.android.nostr.NostrEvent
import com.bitchat.android.nostr.NostrFilter
import com.bitchat.android.nostr.NostrIdentity
import com.bitchat.android.nostr.NostrIdentityBridge
import com.bitchat.android.nostr.NostrKind
import com.bitchat.android.nostr.NostrProtocol
import com.bitchat.android.nostr.NostrRelayManager
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.service.MeshServiceHolder
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.ContactIdentityResolver
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.util.Date
import kotlin.math.abs
import kotlin.random.Random

/**
 * Opt-in bridge policy shared by foreground transport and Compose UI.
 *
 * Outbound public traffic crosses the bridge only when the author opted in
 * and did not mark the message nearby-only. Passive `fromBridge` reception is
 * accepted regardless of the switch because it exposes no local traffic.
 */
object MeshBridgeService {
    data class BridgedParticipant(
        val pubkey: String,
        val nickname: String?,
        val lastSeenMs: Long
    ) {
        val displayName: String
            get() = "${nickname?.trim()?.takeIf { it.isNotEmpty() } ?: "anon"}#${pubkey.takeLast(4)}"
    }

    private data class VerifiedPeer(
        val peerId: String,
        val nickname: String,
        val noiseKey: ByteArray,
        val signingKey: ByteArray,
        val capabilities: PeerCapabilities?,
        val bridgeCell: String?,
        val lastSeenMs: Long
    )

    private data class PendingUplink(
        val depositor: String,
        val cell: String,
        val event: NostrEvent
    )

    private data class PendingDownlink(
        val cell: String,
        val event: NostrEvent
    )

    private data class PendingDrop(
        val envelope: CourierEnvelope,
        val dedupKey: String?
    )

    private const val TAG = "MeshBridgeService"
    private const val PREFS = "bitchat_bridge"
    private const val KEY_ENABLED = "bridge_enabled_v1"
    private const val BRIDGE_SUBSCRIPTION = "mesh-bridge-rendezvous"
    private const val COURIER_SUBSCRIPTION = "mesh-bridge-courier"
    private const val CELL_PRECISION = 6
    private const val MAX_EVENT_AGE_MS = 15L * 60 * 1000
    private const val MAX_CONTENT_BYTES = 16_000
    private const val MAX_TRACKED_IDS = 512
    private const val MAX_PARTICIPANTS = 128
    private const val PARTICIPANT_FRESH_MS = 10L * 60 * 1000
    private const val PRESENCE_INTERVAL_MS = 4L * 60 * 1000
    private const val MAX_QUEUED_UPLINKS = 20
    private const val MAX_UPLINKS_PER_DEPOSITOR = 5
    private const val UPLINKS_PER_MINUTE_PER_DEPOSITOR = 10
    private const val MAX_PENDING_DOWNLINKS = 30
    private const val DOWNLINKS_PER_MINUTE = 20
    private const val INBOUND_PER_MINUTE = 600
    private const val INBOUND_PER_SIGNER_PER_MINUTE = 120
    private const val SIGNATURE_ATTEMPTS_PER_MINUTE = 720
    private const val MAX_WATCHED_COURIER_PEERS = 16
    private const val MAX_PENDING_DROPS = 20
    private const val MAX_DROP_BYTES = 20 * 1024
    private const val PREKEY_REBROADCAST_MS = 60L * 60 * 1000
    private const val DROP_DEDUP_MS = 24L * 60 * 60 * 1000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()
    private val _nearbyOnly = MutableStateFlow(false)
    val nearbyOnly: StateFlow<Boolean> = _nearbyOnly.asStateFlow()
    private val _activeCell = MutableStateFlow<String?>(null)
    val activeCell: StateFlow<String?> = _activeCell.asStateFlow()
    private val _bridgedParticipants = MutableStateFlow<List<BridgedParticipant>>(emptyList())
    val bridgedParticipants: StateFlow<List<BridgedParticipant>> = _bridgedParticipants.asStateFlow()

    @Volatile
    private var appContext: Context? = null
    private var relayManager: NostrRelayManager? = null
    private var prekeys: PrekeyManager? = null
    private var prefs: android.content.SharedPreferences? = null
    private var localLocationCell: String? = null
    private var subscribedCells: Set<String> = emptySet()
    private var subscribedCourierTags: Set<String> = emptySet()
    private val verifiedPeers = linkedMapOf<String, VerifiedPeer>()
    private val pendingPrekeyPackets = linkedMapOf<String, BitchatPacket>()
    private val publishedEventIds = BoundedIdSet(MAX_TRACKED_IDS)
    private val receivedEventIds = BoundedIdSet(MAX_TRACKED_IDS)
    private val meshBroadcastEventIds = BoundedIdSet(MAX_TRACKED_IDS)
    private val rebroadcastEventIds = BoundedIdSet(MAX_TRACKED_IDS)
    private val injectedEventIds = BoundedIdSet(MAX_TRACKED_IDS)
    private val radioMessageIds = BoundedIdSet(MAX_TRACKED_IDS)
    private val queuedUplinks = mutableListOf<PendingUplink>()
    private val pendingDownlinks = mutableListOf<PendingDownlink>()
    private val pendingDrops = mutableListOf<PendingDrop>()
    private val participants = linkedMapOf<String, BridgedParticipant>()
    private val uplinkTimes = mutableMapOf<String, MutableList<Long>>()
    private val inboundTimes = mutableListOf<Long>()
    private val inboundTimesBySigner = mutableMapOf<String, MutableList<Long>>()
    private val downlinkTimes = mutableListOf<Long>()
    private val signatureAttemptTimes = mutableListOf<Long>()
    private var downlinkJob: Job? = null
    private var presenceJob: Job? = null
    private var lastPrekeyBroadcastMs = 0L
    private var publishedDropKeys: PersistentExpiringIdSet? = null
    private var seenDropEventIds: PersistentExpiringIdSet? = null
    private var openedCourierMessageIds: PersistentExpiringIdSet? = null

    fun initialize(context: Context) {
        if (appContext != null) return
        synchronized(this) {
            if (appContext != null) return
            val application = context.applicationContext
            appContext = application
            relayManager = NostrRelayManager.getInstance(application)
            prekeys = PrekeyManager.getInstance(application)
            prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            _isEnabled.value = loadEnabledWithMigration(prefs!!)
            PeerCapabilities.setBridgeEnabled(_isEnabled.value)
            publishedDropKeys = PersistentExpiringIdSet(prefs!!, "published_drop_keys", MAX_TRACKED_IDS)
            seenDropEventIds = PersistentExpiringIdSet(prefs!!, "seen_drop_events", MAX_TRACKED_IDS)
            openedCourierMessageIds =
                PersistentExpiringIdSet(prefs!!, "opened_courier_messages", MAX_TRACKED_IDS)
        }

        val location = LocationChannelManager.getInstance(context)
        scope.launch {
            location.availableChannels.collect { channels ->
                localLocationCell = channels
                    .firstOrNull { it.level == GeohashChannelLevel.NEIGHBORHOOD }
                    ?.geohash
                    ?.take(CELL_PRECISION)
                refreshRendezvous()
            }
        }
        scope.launch {
            relayManager?.isConnected?.collect { connected ->
                if (connected) {
                    refreshRendezvous(forceSubscriptions = true)
                    flushQueuedUplinks()
                    flushPendingDrops()
                    publishPresence()
                }
            }
        }
        scope.launch {
            if (_isEnabled.value) {
                relayManager?.connect()
                location.refreshChannels()
                refreshRendezvous(forceSubscriptions = true)
                refreshCourierSubscription()
            }
            startPresenceLoop()
            delay(2_000)
            broadcastPrekeyBundle(force = true)
        }
    }

    fun setEnabled(enabled: Boolean) {
        if (_isEnabled.value == enabled) return
        _isEnabled.value = enabled
        prefs?.edit { putBoolean(KEY_ENABLED, enabled) }
        PeerCapabilities.setBridgeEnabled(enabled)
        _nearbyOnly.value = false
        scope.launch {
            if (!enabled) {
                closeSubscriptions()
                queuedUplinks.clear()
                pendingDownlinks.clear()
                pendingDrops.clear()
                participants.clear()
                publishParticipants()
                _activeCell.value = null
            } else {
                relayManager?.connect()
                LocationChannelManager.getInstance(requireContext()).refreshChannels()
                refreshRendezvous(forceSubscriptions = true)
                refreshCourierSubscription()
                broadcastPrekeyBundle(force = true)
            }
            currentMesh()?.sendBroadcastAnnounce()
        }
    }

    fun setNearbyOnly(enabled: Boolean) {
        _nearbyOnly.value = enabled
    }

    /** Cell included in announce TLV 0x06 while the bridge switch is on. */
    fun advertisedCell(): String? = _activeCell.value.takeIf { _isEnabled.value }

    fun bridgeOutgoing(
        content: String,
        senderPeerId: String,
        timestampMs: Long,
        nickname: String?
    ) {
        scope.launch {
            if (!_isEnabled.value || _nearbyOnly.value) return@launch
            val cell = _activeCell.value ?: currentCell() ?: return@launch
            if (content.toByteArray(Charsets.UTF_8).size > MAX_CONTENT_BYTES) return@launch
            val identity = NostrIdentityBridge.deriveBridgeIdentity(cell, requireContext())
            val event = NostrProtocol.createBridgeMeshEvent(
                content = content,
                cell = cell,
                senderIdentity = identity,
                nickname = nickname,
                meshSenderId = senderPeerId,
                meshTimestampMs = timestampMs
            )
            publishedEventIds.add(event.id)
            injectedEventIds.add(event.id)
            if (relayManager?.isConnected?.value == true) {
                relayManager?.sendEventToGeohash(event, cell)
            } else {
                val peer = availableBridgePeer() ?: return@launch
                NostrCarrierPacket.fromEvent(
                    NostrCarrierPacket.Direction.TO_BRIDGE,
                    cell,
                    event
                )?.encode()?.let { currentMesh()?.sendNostrCarrier(it, peer.peerId) }
            }
        }
    }

    /** Called only after a public radio packet's Ed25519 signature was accepted. */
    fun handleAuthenticatedRadioMessage(messageId: String) {
        if (messageId.isBlank()) return
        scope.launch {
            radioMessageIds.add(messageId)
            pendingDownlinks.removeAll { pending ->
                classifyMessage(pending.event, pending.cell)?.bridgeRadioMessageIdHint == messageId
            }
        }
    }

    fun handleVerifiedAnnouncement(peerId: String, announcement: IdentityAnnouncement) {
        scope.launch {
            val peer = VerifiedPeer(
                peerId = peerId,
                nickname = announcement.nickname,
                noiseKey = announcement.noisePublicKey.copyOf(),
                signingKey = announcement.signingPublicKey.copyOf(),
                capabilities = announcement.capabilities,
                bridgeCell = announcement.bridgeGeohash?.takeIf(::isValidGeohash),
                lastSeenMs = System.currentTimeMillis()
            )
            verifiedPeers[peerId] = peer
            while (verifiedPeers.size > 200) verifiedPeers.remove(verifiedPeers.keys.first())
            pendingPrekeyPackets.remove(peerId)?.let { ingestPrekeyPacket(it) }
            if (_isEnabled.value) {
                refreshRendezvous()
                refreshCourierSubscription()
            }
            broadcastPrekeyBundle()
        }
    }

    fun handlePrekeyPacket(packet: BitchatPacket) {
        scope.launch { ingestPrekeyPacket(packet) }
    }

    fun handleCarrier(payload: ByteArray, fromPeerId: String, directedToUs: Boolean) {
        scope.launch {
            val carrier = NostrCarrierPacket.decode(payload) ?: return@launch
            when (carrier.direction) {
                NostrCarrierPacket.Direction.TO_BRIDGE -> {
                    if (directedToUs) handleUplink(carrier, fromPeerId)
                }
                NostrCarrierPacket.Direction.FROM_BRIDGE -> {
                    if (!directedToUs) handleDownlink(carrier)
                }
                NostrCarrierPacket.Direction.TO_GATEWAY,
                NostrCarrierPacket.Direction.FROM_GATEWAY -> Unit
            }
        }
    }

    fun handleCourierEnvelope(payload: ByteArray) {
        scope.launch {
            val envelope = CourierEnvelope.decode(payload) ?: return@launch
            if (!validEnvelopeLifetime(envelope)) return@launch
            if (isMyCourierTag(envelope.recipientTag)) {
                openCourierEnvelope(envelope)
            } else if (_isEnabled.value) {
                publishOrQueueDrop(envelope, dedupKey = null)
            }
        }
    }

    /**
     * Deposit an offline DM either directly to relays or through a reachable
     * bridge peer. Returns true when a compatible envelope was produced and
     * accepted by one of those paths.
     */
    fun depositCourierDrop(
        content: String,
        messageId: String,
        recipientNoiseKey: ByteArray
    ): Boolean {
        if (!_isEnabled.value || content.toByteArray(Charsets.UTF_8).size > 255) return false
        val privatePacket = PrivateMessagePacket(messageId, content).encode() ?: return false
        val typedPayload = NoisePayload(NoisePayloadType.PRIVATE_MESSAGE, privatePacket).encode()
        val livePeer = verifiedPeers.values.firstOrNull {
            it.noiseKey.contentEquals(recipientNoiseKey) &&
                currentMesh()?.getPeerInfo(it.peerId)?.isConnected == true
        }
        val allowsPrekeys = livePeer?.capabilities?.contains(PeerCapabilities.PREKEYS) != false
        val sealed = runCatching {
            prekeys?.seal(
                typedPayload,
                messageId,
                recipientNoiseKey,
                recipientAdvertisesPrekeys = allowsPrekeys
            )
        }.getOrNull() ?: return false
        val now = System.currentTimeMillis()
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
        val encoded = envelope.encode() ?: return false
        if (encoded.size > MAX_DROP_BYTES) return false
        val dedupKey = senderDropKey(messageId, recipientNoiseKey)
        if (publishedDropKeys?.contains(dedupKey) == true) return true

        val relayConnected = relayManager?.isConnected?.value == true
        if (relayConnected) {
            scope.launch { publishOrQueueDrop(envelope, dedupKey) }
            return true
        }
        val gateway = availableBridgePeer()
        if (gateway != null) {
            currentMesh()?.sendCourierEnvelope(encoded, gateway.peerId)
            return true
        }
        scope.launch { enqueueDrop(PendingDrop(envelope, dedupKey)) }
        return true
    }

    fun wipe() {
        // Clear persistent cryptographic and dedup material immediately. The
        // rest of the process-local bridge state remains serialized on scope.
        prekeys?.wipe()
        publishedDropKeys?.clear()
        seenDropEventIds?.clear()
        openedCourierMessageIds?.clear()
        scope.launch {
            closeSubscriptions()
            queuedUplinks.clear()
            pendingDownlinks.clear()
            pendingDrops.clear()
            verifiedPeers.clear()
            participants.clear()
            publishedEventIds.clear()
            receivedEventIds.clear()
            meshBroadcastEventIds.clear()
            rebroadcastEventIds.clear()
            injectedEventIds.clear()
            radioMessageIds.clear()
            _nearbyOnly.value = false
            publishParticipants()
        }
    }

    private suspend fun refreshRendezvous(forceSubscriptions: Boolean = false) {
        if (!_isEnabled.value) return
        val cell = currentCell()
        val changed = cell != _activeCell.value
        if (changed) {
            _activeCell.value = cell
            currentMesh()?.sendBroadcastAnnounce()
        }
        if (cell == null) return
        val cells = linkedSetOf(cell).apply { addAll(Geohash.neighborsSamePrecision(cell)) }
        if (changed || forceSubscriptions || cells != subscribedCells) {
            relayManager?.unsubscribe(BRIDGE_SUBSCRIPTION)
            subscribedCells = cells
            val targets = linkedSetOf<String>()
            cells.forEach { subscribedCell ->
                relayManager?.ensureGeohashRelaysConnected(subscribedCell)
                targets += relayManager?.getRelaysForGeohash(subscribedCell).orEmpty()
            }
            relayManager?.subscribe(
                filter = NostrFilter.bridgeRendezvous(
                    cells,
                    since = System.currentTimeMillis() - MAX_EVENT_AGE_MS
                ),
                id = BRIDGE_SUBSCRIPTION,
                handler = { event -> scope.launch { handleRendezvousEvent(event) } },
                targetRelayUrls = targets.toList()
            )
            publishPresence()
        }
    }

    private fun currentCell(): String? {
        localLocationCell?.takeIf(::isValidGeohash)?.let { return it.take(CELL_PRECISION) }
        return availableBridgePeer()?.bridgeCell?.take(CELL_PRECISION)
    }

    private fun availableBridgePeer(): VerifiedPeer? =
        verifiedPeers.values.firstOrNull { peer ->
            peer.capabilities?.contains(PeerCapabilities.BRIDGE) == true &&
                peer.bridgeCell != null &&
                currentMesh()?.getPeerInfo(peer.peerId)?.isConnected == true
        }

    private fun handleRendezvousEvent(event: NostrEvent) {
        if (!_isEnabled.value) return
        val cell = event.tagValue("r") ?: return
        if (cell !in subscribedCells || publishedEventIds.contains(event.id)) return
        if (isOwnEvent(event, cell)) {
            publishedEventIds.add(event.id)
            return
        }
        if (!allowSignatureAttempt() || !event.isValidSignature()) return
        if (!receivedEventIds.add(event.id) || !allowInbound(event.pubkey)) return
        if (!isFresh(event) || event.tagValue("r") != cell || !isValidGeohash(cell)) return

        when (event.kind) {
            NostrKind.GEOHASH_PRESENCE -> recordParticipant(event.pubkey, null)
            NostrKind.EPHEMERAL_EVENT -> {
                val message = classifyMessage(event, cell) ?: return
                val localRadio = message.bridgeRadioMessageIdHint?.let(::radioCopyPresent) == true
                if (!localRadio && injectBridgeMessage(message)) {
                    recordParticipant(event.pubkey, event.tagValue("n"))
                }
                if (!localRadio &&
                    !meshBroadcastEventIds.contains(event.id) &&
                    !rebroadcastEventIds.contains(event.id) &&
                    pendingDownlinks.none { it.event.id == event.id }
                ) {
                    pendingDownlinks += PendingDownlink(cell, event)
                    while (pendingDownlinks.size > MAX_PENDING_DOWNLINKS) pendingDownlinks.removeAt(0)
                    scheduleDownlink(jitter = true)
                }
            }
        }
    }

    private fun handleUplink(carrier: NostrCarrierPacket, depositor: String) {
        if (!_isEnabled.value || !allowUplink(depositor)) return
        val event = structurallyValidEvent(carrier) ?: return
        if (meshBroadcastEventIds.contains(event.id) ||
            publishedEventIds.contains(event.id) ||
            queuedUplinks.any { it.event.id == event.id }
        ) {
            return
        }
        if (!allowSignatureAttempt() || !event.isValidSignature()) return
        if (relayManager?.isConnected?.value == true) {
            publishCarriedEvent(event, carrier.geohash)
        } else {
            if (queuedUplinks.count { it.depositor == depositor } >= MAX_UPLINKS_PER_DEPOSITOR) return
            queuedUplinks += PendingUplink(depositor, carrier.geohash, event)
            while (queuedUplinks.size > MAX_QUEUED_UPLINKS) queuedUplinks.removeAt(0)
        }
    }

    private fun handleDownlink(carrier: NostrCarrierPacket) {
        val event = structurallyValidEvent(carrier) ?: return
        if (publishedEventIds.contains(event.id) || isOwnEvent(event, carrier.geohash)) return
        if (!allowSignatureAttempt() || !event.isValidSignature()) return
        val firstMesh = meshBroadcastEventIds.add(event.id)
        if (!firstMesh || !receivedEventIds.add(event.id) || !allowInbound(event.pubkey)) return
        val message = classifyMessage(event, carrier.geohash) ?: return
        if (injectBridgeMessage(message)) recordParticipant(event.pubkey, event.tagValue("n"))
    }

    private fun structurallyValidEvent(carrier: NostrCarrierPacket): NostrEvent? {
        if (!isValidGeohash(carrier.geohash) ||
            carrier.eventJson.size > NostrCarrierPacket.MAX_EVENT_JSON_BYTES
        ) {
            return null
        }
        val event = carrier.event() ?: return null
        if (!isFresh(event) || event.tagValue("r") != carrier.geohash) return null
        return when (event.kind) {
            NostrKind.GEOHASH_PRESENCE -> event
            NostrKind.EPHEMERAL_EVENT ->
                event.takeIf { classifyMessage(it, carrier.geohash) != null }
            else -> null
        }
    }

    private fun classifyMessage(event: NostrEvent, cell: String): BitchatMessage? {
        if (event.kind != NostrKind.EPHEMERAL_EVENT ||
            !isFresh(event) ||
            event.tagValue("r") != cell ||
            !isValidGeohash(cell)
        ) {
            return null
        }
        val content = event.content
        if (content.isBlank() || content.toByteArray(Charsets.UTF_8).size > MAX_CONTENT_BYTES) return null
        val nickname = event.tagValue("n")?.trim()?.takeIf { it.isNotEmpty() }
        val m = event.tags.firstOrNull { it.size >= 4 && it[0] == "m" }
        val radioHint = if (m != null &&
            m[2].matches(Regex("^[0-9a-fA-F]{16}$"))
        ) {
            m[3].toLongOrNull()?.let { MeshMessageIdentity.stableId(m[2], it, content) }
        } else {
            null
        }
        return BitchatMessage(
            id = event.id,
            sender = "${nickname ?: "anon"}#${event.pubkey.takeLast(4)}",
            content = content,
            timestamp = Date(event.createdAt * 1000L),
            senderPeerID = "bridge:${event.pubkey.take(16)}",
            isBridged = true,
            bridgeRadioMessageIdHint = radioHint
        )
    }

    private fun injectBridgeMessage(message: BitchatMessage): Boolean {
        if (!injectedEventIds.add(message.id)) return false
        if (message.bridgeRadioMessageIdHint?.let(::radioCopyPresent) == true) return false
        AppStateStore.addPublicMessage(message)
        return true
    }

    private fun radioCopyPresent(messageId: String): Boolean =
        radioMessageIds.contains(messageId) || AppStateStore.hasRadioPublicMessage(messageId)

    private fun scheduleDownlink(jitter: Boolean) {
        if (downlinkJob?.isActive == true || pendingDownlinks.isEmpty()) return
        val now = System.currentTimeMillis()
        downlinkTimes.removeAll { now - it >= 60_000 }
        val waitMs = if (jitter) {
            Random.nextLong(200, 1_501)
        } else {
            (downlinkTimes.minOrNull()?.plus(60_000)?.minus(now) ?: 50).coerceAtLeast(50)
        }
        downlinkJob = scope.launch {
            delay(waitMs)
            drainDownlinks()
        }
    }

    private fun drainDownlinks() {
        val now = System.currentTimeMillis()
        downlinkTimes.removeAll { now - it >= 60_000 }
        while (pendingDownlinks.isNotEmpty() && downlinkTimes.size < DOWNLINKS_PER_MINUTE) {
            val item = pendingDownlinks.removeAt(0)
            if (!isFresh(item.event) ||
                meshBroadcastEventIds.contains(item.event.id) ||
                rebroadcastEventIds.contains(item.event.id)
            ) {
                continue
            }
            val message = classifyMessage(item.event, item.cell)
            if (message?.bridgeRadioMessageIdHint?.let(::radioCopyPresent) == true) continue
            val payload = NostrCarrierPacket.fromEvent(
                NostrCarrierPacket.Direction.FROM_BRIDGE,
                item.cell,
                item.event
            )?.encode() ?: continue
            currentMesh()?.sendNostrCarrier(payload)
            rebroadcastEventIds.add(item.event.id)
            downlinkTimes += System.currentTimeMillis()
        }
        if (pendingDownlinks.isNotEmpty()) scheduleDownlink(jitter = false)
    }

    private fun flushQueuedUplinks() {
        if (!_isEnabled.value || relayManager?.isConnected?.value != true) return
        val queued = queuedUplinks.toList()
        queuedUplinks.clear()
        queued.filterNot { publishedEventIds.contains(it.event.id) }
            .forEach { publishCarriedEvent(it.event, it.cell) }
    }

    private fun publishCarriedEvent(event: NostrEvent, cell: String) {
        publishedEventIds.add(event.id)
        relayManager?.sendEventToGeohash(event, cell)
    }

    private fun publishPresence() {
        if (!_isEnabled.value || relayManager?.isConnected?.value != true) return
        val cell = _activeCell.value ?: return
        val identity = NostrIdentityBridge.deriveBridgeIdentity(cell, requireContext())
        val event = NostrProtocol.createBridgePresenceEvent(cell, identity)
        publishedEventIds.add(event.id)
        relayManager?.sendEventToGeohash(event, cell)
    }

    private fun startPresenceLoop() {
        if (presenceJob?.isActive == true) return
        presenceJob = scope.launch {
            while (true) {
                delay(PRESENCE_INTERVAL_MS)
                pruneParticipants()
                if (_isEnabled.value) {
                    refreshRendezvous()
                    refreshCourierSubscription()
                    publishPresence()
                    broadcastPrekeyBundle()
                }
            }
        }
    }

    private fun recordParticipant(pubkey: String, nickname: String?) {
        val now = System.currentTimeMillis()
        participants.entries.removeAll { now - it.value.lastSeenMs > PARTICIPANT_FRESH_MS }
        if (pubkey !in participants && participants.size >= MAX_PARTICIPANTS) {
            participants.minByOrNull { it.value.lastSeenMs }?.key?.let(participants::remove)
        }
        val previous = participants[pubkey]
        participants[pubkey] = BridgedParticipant(
            pubkey,
            nickname?.trim()?.takeIf { it.isNotEmpty() } ?: previous?.nickname,
            now
        )
        publishParticipants()
    }

    private fun pruneParticipants() {
        val now = System.currentTimeMillis()
        participants.entries.removeAll { now - it.value.lastSeenMs > PARTICIPANT_FRESH_MS }
        publishParticipants()
    }

    private fun publishParticipants() {
        _bridgedParticipants.value = participants.values.sortedByDescending { it.lastSeenMs }
    }

    private fun allowUplink(depositor: String): Boolean {
        val now = System.currentTimeMillis()
        val times = uplinkTimes.getOrPut(depositor) { mutableListOf() }
        times.removeAll { now - it >= 60_000 }
        if (times.size >= UPLINKS_PER_MINUTE_PER_DEPOSITOR) return false
        times += now
        return true
    }

    private fun allowInbound(signer: String): Boolean {
        val now = System.currentTimeMillis()
        inboundTimes.removeAll { now - it >= 60_000 }
        if (inboundTimes.size >= INBOUND_PER_MINUTE) return false
        val signerTimes = inboundTimesBySigner.getOrPut(signer) { mutableListOf() }
        signerTimes.removeAll { now - it >= 60_000 }
        if (signerTimes.size >= INBOUND_PER_SIGNER_PER_MINUTE) return false
        inboundTimes += now
        signerTimes += now
        return true
    }

    private fun allowSignatureAttempt(): Boolean {
        val now = System.currentTimeMillis()
        signatureAttemptTimes.removeAll { now - it >= 60_000 }
        if (signatureAttemptTimes.size >= SIGNATURE_ATTEMPTS_PER_MINUTE) return false
        signatureAttemptTimes += now
        return true
    }

    private fun ingestPrekeyPacket(packet: BitchatPacket) {
        val bundle = PrekeyBundle.decode(packet.payload) ?: return
        val owner = ContactIdentityResolver.peerIdForNoiseKey(bundle.noiseStaticPublicKey)
        val packetOwner = packet.senderID.toHex()
        if (owner != packetOwner) return
        val peer = verifiedPeers[owner]
        if (peer == null ||
            !peer.noiseKey.contentEquals(bundle.noiseStaticPublicKey)
        ) {
            if (pendingPrekeyPackets.size < 64 || owner in pendingPrekeyPackets) {
                pendingPrekeyPackets[owner] = packet
            }
            return
        }
        val signature = packet.signature ?: return
        val signingData = packet.toBinaryDataForSigning() ?: return
        if (!verifyEd25519(signature, signingData, peer.signingKey)) return
        prekeys?.verifyAndIngest(bundle, peer.noiseKey, peer.signingKey)
    }

    private fun broadcastPrekeyBundle(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPrekeyBroadcastMs < PREKEY_REBROADCAST_MS) return
        val bundle = prekeys?.currentSignedBundle(now) ?: return
        val encoded = bundle.encode() ?: return
        lastPrekeyBroadcastMs = now
        currentMesh()?.sendPrekeyBundle(encoded)
    }

    private fun refreshCourierSubscription() {
        if (!_isEnabled.value) return
        val identityKey = SecureIdentityStateManager(requireContext()).loadStaticKey()?.second ?: return
        val myTags = CourierEnvelope.candidateTags(identityKey).map { it.toHex() }.toSet()
        val peerTags = verifiedPeers.values
            .asSequence()
            .filter { currentMesh()?.getPeerInfo(it.peerId)?.isConnected == true }
            .take(MAX_WATCHED_COURIER_PEERS)
            .flatMap {
                CourierEnvelope.candidateTags(it.noiseKey)
                    .asSequence()
                    .map { bytes -> bytes.toHex() }
            }
            .toSet()
        val allTags = myTags + peerTags
        if (allTags == subscribedCourierTags) return
        relayManager?.unsubscribe(COURIER_SUBSCRIPTION)
        subscribedCourierTags = allTags
        if (allTags.isEmpty()) return
        relayManager?.subscribe(
            filter = NostrFilter.courierDrops(
                allTags,
                since = System.currentTimeMillis() - CourierEnvelope.MAX_LIFETIME_MS
            ),
            id = COURIER_SUBSCRIPTION,
            handler = { event -> scope.launch { handleDropEvent(event) } },
            targetRelayUrls = NostrRelayManager.defaultRelays()
        )
    }

    private fun handleDropEvent(event: NostrEvent) {
        if (!_isEnabled.value ||
            event.kind != NostrKind.COURIER_DROP ||
            seenDropEventIds?.contains(event.id) == true ||
            !allowSignatureAttempt() ||
            !event.isValidSignature()
        ) {
            return
        }
        val data = runCatching { Base64.decode(event.content, Base64.DEFAULT) }.getOrNull() ?: return
        if (data.size > MAX_DROP_BYTES) return
        val envelope = CourierEnvelope.decode(data) ?: return
        if (!validEnvelopeLifetime(envelope)) return
        val tagHex = envelope.recipientTag.toHex()
        if (event.tags.none { it.size >= 2 && it[0] == "x" && it[1] == tagHex }) return

        if (isMyCourierTag(envelope.recipientTag)) {
            if (openCourierEnvelope(envelope)) {
                seenDropEventIds?.add(event.id, DROP_DEDUP_MS)
            }
            return
        }
        val peer = verifiedPeers.values
            .asSequence()
            .filter { currentMesh()?.getPeerInfo(it.peerId)?.isConnected == true }
            .take(MAX_WATCHED_COURIER_PEERS)
            .firstOrNull {
                CourierEnvelope.candidateTags(it.noiseKey)
                    .any { candidate -> candidate.contentEquals(envelope.recipientTag) }
            }
        if (peer != null) {
            currentMesh()?.sendCourierEnvelope(data, peer.peerId)
            seenDropEventIds?.add(event.id, DROP_DEDUP_MS)
        }
    }

    private fun openCourierEnvelope(envelope: CourierEnvelope): Boolean {
        val opened = runCatching {
            prekeys?.open(envelope.ciphertext, envelope.prekeyId)
        }.getOrNull() ?: return false
        val payload = NoisePayload.decode(opened.payload) ?: return true
        if (payload.type != NoisePayloadType.PRIVATE_MESSAGE) return true
        val privateMessage = PrivateMessagePacket.decode(payload.data) ?: return true
        if (openedCourierMessageIds?.contains(privateMessage.messageID) == true) return true
        val senderPeerId = ContactIdentityResolver.peerIdForNoiseKey(opened.senderStaticKey)
        val senderResolution = ContactDirectory.resolve(opened.senderStaticKey.toHex())
        val message = BitchatMessage(
            id = privateMessage.messageID,
            sender = senderResolution.displayName ?: verifiedPeers[senderPeerId]?.nickname ?: "Unknown",
            content = privateMessage.content,
            timestamp = Date(),
            isPrivate = true,
            recipientNickname = currentMesh()?.myPeerID,
            senderPeerID = senderPeerId
        )
        AppStateStore.addPrivateMessage(
            ContactIdentityResolver.contactConversationIdForNoiseKey(opened.senderStaticKey),
            message
        )
        openedCourierMessageIds?.add(privateMessage.messageID, DROP_DEDUP_MS)
        if (opened.consumedPrekey) broadcastPrekeyBundle(force = true)
        return true
    }

    private fun publishOrQueueDrop(envelope: CourierEnvelope, dedupKey: String?) {
        if (!validEnvelopeLifetime(envelope)) return
        if (relayManager?.isConnected?.value != true) {
            enqueueDrop(PendingDrop(envelope, dedupKey))
            return
        }
        val encoded = envelope.encode() ?: return
        if (encoded.size > MAX_DROP_BYTES) return
        val event = NostrProtocol.createCourierDropEvent(
            envelope = encoded,
            recipientTagHex = envelope.recipientTag.toHex(),
            expiresAtMs = envelope.expiry,
            senderIdentity = NostrIdentity.generate()
        )
        relayManager?.sendEvent(event, NostrRelayManager.defaultRelays())
        dedupKey?.let { publishedDropKeys?.add(it, DROP_DEDUP_MS) }
    }

    private fun enqueueDrop(drop: PendingDrop) {
        if (drop.dedupKey != null && pendingDrops.any { it.dedupKey == drop.dedupKey }) return
        pendingDrops += drop
        while (pendingDrops.size > MAX_PENDING_DROPS) pendingDrops.removeAt(0)
    }

    private fun flushPendingDrops() {
        if (!_isEnabled.value || relayManager?.isConnected?.value != true) return
        val queued = pendingDrops.toList()
        pendingDrops.clear()
        queued.forEach { publishOrQueueDrop(it.envelope, it.dedupKey) }
    }

    private fun isMyCourierTag(tag: ByteArray): Boolean {
        val ownKey = SecureIdentityStateManager(requireContext()).loadStaticKey()?.second ?: return false
        return CourierEnvelope.candidateTags(ownKey).any { it.contentEquals(tag) }
    }

    private fun validEnvelopeLifetime(envelope: CourierEnvelope): Boolean {
        val now = System.currentTimeMillis()
        return !envelope.isExpired(now) &&
            envelope.expiry > 0 &&
            envelope.expiry - now <= CourierEnvelope.MAX_LIFETIME_MS
    }

    private fun closeSubscriptions() {
        relayManager?.unsubscribe(BRIDGE_SUBSCRIPTION)
        relayManager?.unsubscribe(COURIER_SUBSCRIPTION)
        subscribedCells = emptySet()
        subscribedCourierTags = emptySet()
    }

    private fun isOwnEvent(event: NostrEvent, cell: String): Boolean =
        runCatching {
            NostrIdentityBridge.deriveBridgeIdentity(cell, requireContext())
                .publicKeyHex.equals(event.pubkey, ignoreCase = true)
        }.getOrDefault(false)

    private fun isFresh(event: NostrEvent): Boolean =
        abs(System.currentTimeMillis() - event.createdAt * 1000L) <= MAX_EVENT_AGE_MS

    private fun isValidGeohash(value: String): Boolean =
        value.length in 1..12 &&
            value.matches(Regex("^[0123456789bcdefghjkmnpqrstuvwxyz]+$", RegexOption.IGNORE_CASE))

    private fun NostrEvent.tagValue(name: String): String? =
        tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)

    private fun senderDropKey(messageId: String, recipientNoiseKey: ByteArray): String {
        val material = recipientNoiseKey.toHex() + "|" + messageId
        return MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
            .toHex()
    }

    private fun verifyEd25519(signature: ByteArray, data: ByteArray, key: ByteArray): Boolean =
        runCatching {
            Ed25519Signer().apply {
                init(false, Ed25519PublicKeyParameters(key, 0))
                update(data, 0, data.size)
            }.verifySignature(signature)
        }.getOrDefault(false)

    private fun currentMesh(): MeshService? =
        MeshServiceHolder.unifiedMeshService
            ?: appContext?.let { context ->
                runCatching { MeshServiceHolder.getUnifiedOrCreate(context) }.getOrNull()
            }

    private fun requireContext(): Context =
        checkNotNull(appContext) { "MeshBridgeService.initialize must be called first" }

    private fun loadEnabledWithMigration(
        preferences: android.content.SharedPreferences
    ): Boolean {
        if (preferences.contains(KEY_ENABLED)) return preferences.getBoolean(KEY_ENABLED, false)
        val legacyKeys = listOf("gateway_user_enabled", "gateway_enabled", "gateway.userEnabled")
        val migrated = legacyKeys.any { preferences.getBoolean(it, false) }
        preferences.edit {
            putBoolean(KEY_ENABLED, migrated)
            legacyKeys.forEach(::remove)
        }
        return migrated
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private class BoundedIdSet(private val capacity: Int) {
        private val values = LinkedHashSet<String>()

        fun add(id: String): Boolean {
            if (!values.add(id)) return false
            while (values.size > capacity) values.remove(values.first())
            return true
        }

        fun contains(id: String): Boolean = id in values
        fun clear() = values.clear()
    }

    private class PersistentExpiringIdSet(
        private val preferences: android.content.SharedPreferences,
        private val key: String,
        private val capacity: Int
    ) {
        private val gson = Gson()
        private val values: LinkedHashMap<String, Long> = load()

        fun contains(id: String, nowMs: Long = System.currentTimeMillis()): Boolean {
            prune(nowMs)
            return (values[id] ?: return false) > nowMs
        }

        fun add(id: String, lifetimeMs: Long, nowMs: Long = System.currentTimeMillis()) {
            prune(nowMs)
            values.remove(id)
            values[id] = nowMs + lifetimeMs
            while (values.size > capacity) values.remove(values.keys.first())
            persist()
        }

        fun clear() {
            values.clear()
            preferences.edit { remove(key) }
        }

        private fun prune(nowMs: Long) {
            val changed = values.entries.removeAll { it.value <= nowMs }
            if (changed) persist()
        }

        private fun load(): LinkedHashMap<String, Long> {
            val type = object : TypeToken<Map<String, Long>>() {}.type
            val decoded: Map<String, Long> = runCatching {
                preferences.getString(key, null)
                    ?.let { json -> gson.fromJson<Map<String, Long>>(json, type) }
            }.getOrNull() ?: emptyMap()
            return LinkedHashMap(decoded)
        }

        private fun persist() {
            preferences.edit { putString(key, gson.toJson(values)) }
        }
    }
}
