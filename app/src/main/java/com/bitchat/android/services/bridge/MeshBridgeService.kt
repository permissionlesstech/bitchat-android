package com.bitchat.android.services.bridge

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.bitchat.android.geohash.Geohash
import com.bitchat.android.geohash.GeohashChannelLevel
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.mesh.BridgeMeshDelegate
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.IdentityAnnouncement
import com.bitchat.android.model.NostrCarrierPacket
import com.bitchat.android.model.PeerCapabilities
import com.bitchat.android.nostr.MeshMessageIdentity
import com.bitchat.android.nostr.NostrEvent
import com.bitchat.android.nostr.NostrFilter
import com.bitchat.android.nostr.NostrIdentityBridge
import com.bitchat.android.nostr.NostrKind
import com.bitchat.android.nostr.NostrProtocol
import com.bitchat.android.nostr.NostrRelayManager
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.services.AppStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Date
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Opt-in bridge policy shared by foreground transport and Compose UI.
 *
 * Outbound public traffic crosses the bridge only when the author opted in
 * and did not mark the message nearby-only. Passive `fromBridge` reception is
 * accepted regardless of the switch because it exposes no local traffic.
 */
object MeshBridgeService : BridgeMeshDelegate {
    private data class PendingUplink(
        val depositor: String,
        val cell: String,
        val event: NostrEvent
    )

    private data class PendingDownlink(
        val cell: String,
        val event: NostrEvent
    )

    private const val TAG = "MeshBridgeService"
    private const val PREFS = "bitchat_bridge"
    private const val KEY_ENABLED = "bridge_enabled_v1"
    private const val BRIDGE_SUBSCRIPTION = "mesh-bridge-rendezvous"
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

    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
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
    private var prekeyCoordinator: PrekeyCoordinator? = null
    private var courierCoordinator: CourierCoordinator? = null
    private var prefs: android.content.SharedPreferences? = null
    @Volatile
    private var meshProvider: () -> MeshService? = { null }
    private var clock: () -> Long = System::currentTimeMillis
    private var jitter: (Long, Long) -> Long = kotlin.random.Random::nextLong
    private var localLocationCell: String? = null
    private var subscribedCells: Set<String> = emptySet()
    private val verifiedPeers = linkedMapOf<String, VerifiedBridgePeer>()
    private val verifiedPeerSnapshot = AtomicReference<List<VerifiedBridgePeer>>(emptyList())
    private val publishedEventIds = BoundedIdSet(MAX_TRACKED_IDS)
    private val receivedEventIds = BoundedIdSet(MAX_TRACKED_IDS)
    private val meshBroadcastEventIds = BoundedIdSet(MAX_TRACKED_IDS)
    private val rebroadcastEventIds = BoundedIdSet(MAX_TRACKED_IDS)
    private val injectedEventIds = BoundedIdSet(MAX_TRACKED_IDS)
    private val radioMessageIds = BoundedIdSet(MAX_TRACKED_IDS)
    private val queuedUplinks = mutableListOf<PendingUplink>()
    private val pendingDownlinks = mutableListOf<PendingDownlink>()
    private val participants = linkedMapOf<String, BridgedParticipant>()
    private val uplinkTimes = mutableMapOf<String, MutableList<Long>>()
    private val inboundTimes = mutableListOf<Long>()
    private val inboundTimesBySigner = mutableMapOf<String, MutableList<Long>>()
    private val downlinkTimes = mutableListOf<Long>()
    private val signatureAttemptTimes = mutableListOf<Long>()
    private var downlinkJob: Job? = null
    private var presenceJob: Job? = null

    fun initialize(
        context: Context,
        meshProvider: () -> MeshService? = {
            com.bitchat.android.service.MeshServiceHolder.unifiedMeshService
        },
        clock: () -> Long = System::currentTimeMillis,
        jitter: (Long, Long) -> Long = kotlin.random.Random::nextLong
    ) {
        if (appContext != null) return
        synchronized(this) {
            if (appContext != null) return
            val application = context.applicationContext
            appContext = application
            this.meshProvider = meshProvider
            this.clock = clock
            this.jitter = jitter
            relayManager = NostrRelayManager.getInstance(application)
            val prekeyManager = PrekeyManager.getInstance(application)
            prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            _isEnabled.value = loadEnabledWithMigration(prefs!!)
            PeerCapabilities.setBridgeEnabled(_isEnabled.value)
            prekeyCoordinator = PrekeyCoordinator(
                manager = prekeyManager,
                meshProvider = ::currentMesh,
                peersProvider = verifiedPeerSnapshot::get,
                clock = clock
            )
            courierCoordinator = CourierCoordinator(
                context = application,
                preferences = checkNotNull(prefs),
                relayManager = checkNotNull(relayManager),
                prekeys = prekeyManager,
                meshProvider = ::currentMesh,
                peersProvider = verifiedPeerSnapshot::get,
                onPrekeyConsumed = {
                    scope.launch { prekeyCoordinator?.broadcast(force = true) }
                },
                clock = clock
            )
            courierCoordinator?.setEnabled(_isEnabled.value)
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
                    publishPresence()
                }
            }
        }
        scope.launch {
            val defaultRelays = NostrRelayManager.defaultRelays().toSet()
            relayManager?.relays
                ?.map { relays ->
                    relays.asSequence()
                        .filter { it.isConnected && it.url in defaultRelays }
                        .map { it.url }
                        .toSet()
                }
                ?.distinctUntilChanged()
                ?.collect { connectedDefaults ->
                    if (connectedDefaults.isNotEmpty()) courierCoordinator?.relayConnected()
                }
        }
        scope.launch {
            if (_isEnabled.value) {
                relayManager?.connect()
                location.refreshChannels()
                refreshRendezvous(forceSubscriptions = true)
                courierCoordinator?.peerStateChanged()
            }
            startPresenceLoop()
            delay(2_000)
            prekeyCoordinator?.broadcast(force = true)
        }
    }

    fun setEnabled(enabled: Boolean) {
        if (_isEnabled.value == enabled) return
        _isEnabled.value = enabled
        prefs?.edit { putBoolean(KEY_ENABLED, enabled) }
        PeerCapabilities.setBridgeEnabled(enabled)
        _nearbyOnly.value = false
        courierCoordinator?.setEnabled(enabled)
        scope.launch {
            if (!enabled) {
                closeSubscriptions()
                queuedUplinks.clear()
                pendingDownlinks.clear()
                participants.clear()
                publishParticipants()
                _activeCell.value = null
            } else {
                relayManager?.connect()
                LocationChannelManager.getInstance(requireContext()).refreshChannels()
                refreshRendezvous(forceSubscriptions = true)
                courierCoordinator?.peerStateChanged()
                prekeyCoordinator?.broadcast(force = true)
            }
            currentMesh()?.sendBroadcastAnnounce()
        }
    }

    fun setNearbyOnly(enabled: Boolean) {
        _nearbyOnly.value = enabled
    }

    /** Cell included in announce TLV 0x06 while the bridge switch is on. */
    override fun advertisedCell(): String? = _activeCell.value.takeIf { _isEnabled.value }

    override fun bridgeOutgoing(
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
    override fun handleAuthenticatedRadioMessage(messageId: String) {
        if (messageId.isBlank()) return
        scope.launch {
            radioMessageIds.add(messageId)
            pendingDownlinks.removeAll { pending ->
                classifyMessage(pending.event, pending.cell)?.bridgeRadioMessageIdHint == messageId
            }
        }
    }

    override fun handleVerifiedAnnouncement(peerId: String, announcement: IdentityAnnouncement) {
        scope.launch {
            val peer = VerifiedBridgePeer(
                peerId = peerId,
                nickname = announcement.nickname,
                noiseKey = announcement.noisePublicKey.copyOf(),
                signingKey = announcement.signingPublicKey.copyOf(),
                capabilities = announcement.capabilities,
                bridgeCell = announcement.bridgeGeohash?.takeIf(::isValidGeohash),
                lastSeenMs = clock()
            )
            verifiedPeers[peerId] = peer
            while (verifiedPeers.size > 200) verifiedPeers.remove(verifiedPeers.keys.first())
            publishVerifiedPeerSnapshot()
            prekeyCoordinator?.handlePeerVerified(peerId)
            if (_isEnabled.value) {
                refreshRendezvous()
                courierCoordinator?.peerStateChanged()
            }
            prekeyCoordinator?.broadcast()
        }
    }

    override fun handlePrekeyPacket(packet: BitchatPacket) {
        scope.launch { prekeyCoordinator?.handlePacket(packet) }
    }

    override fun handleCarrier(payload: ByteArray, fromPeerId: String, directedToUs: Boolean) {
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

    override fun handleCourierEnvelope(payload: ByteArray) {
        courierCoordinator?.handleEnvelope(payload)
    }

    suspend fun depositCourierDrop(
        content: String,
        messageId: String,
        recipientNoiseKey: ByteArray
    ): CourierDepositResult =
        courierCoordinator?.deposit(content, messageId, recipientNoiseKey)
            ?: CourierDepositResult.Rejected(CourierDepositResult.Reason.BRIDGE_DISABLED)

    suspend fun wipe() {
        courierCoordinator?.wipe()
        kotlinx.coroutines.withContext(dispatcher) {
            closeSubscriptions()
            queuedUplinks.clear()
            pendingDownlinks.clear()
            verifiedPeers.clear()
            publishVerifiedPeerSnapshot()
            participants.clear()
            publishedEventIds.clear()
            receivedEventIds.clear()
            meshBroadcastEventIds.clear()
            rebroadcastEventIds.clear()
            injectedEventIds.clear()
            radioMessageIds.clear()
            prekeyCoordinator?.wipe()
            _nearbyOnly.value = false
            publishParticipants()
        }
    }

    private fun publishVerifiedPeerSnapshot() {
        verifiedPeerSnapshot.set(
            verifiedPeers.values.map { peer ->
                peer.copy(
                    noiseKey = peer.noiseKey.copyOf(),
                    signingKey = peer.signingKey.copyOf()
                )
            }
        )
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
                    since = clock() - MAX_EVENT_AGE_MS
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

    private fun availableBridgePeer(): VerifiedBridgePeer? =
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
        val now = clock()
        downlinkTimes.removeAll { now - it >= 60_000 }
        val waitMs = if (jitter) {
            jitter(200, 1_501)
        } else {
            (downlinkTimes.minOrNull()?.plus(60_000)?.minus(now) ?: 50).coerceAtLeast(50)
        }
        downlinkJob = scope.launch {
            delay(waitMs)
            drainDownlinks()
        }
    }

    private fun drainDownlinks() {
        val now = clock()
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
            downlinkTimes += clock()
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
                    courierCoordinator?.peerStateChanged()
                    publishPresence()
                    prekeyCoordinator?.broadcast()
                }
            }
        }
    }

    private fun recordParticipant(pubkey: String, nickname: String?) {
        val now = clock()
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
        val now = clock()
        participants.entries.removeAll { now - it.value.lastSeenMs > PARTICIPANT_FRESH_MS }
        publishParticipants()
    }

    private fun publishParticipants() {
        _bridgedParticipants.value = participants.values.sortedByDescending { it.lastSeenMs }
    }

    private fun allowUplink(depositor: String): Boolean {
        val now = clock()
        val times = uplinkTimes.getOrPut(depositor) { mutableListOf() }
        times.removeAll { now - it >= 60_000 }
        if (times.size >= UPLINKS_PER_MINUTE_PER_DEPOSITOR) return false
        times += now
        return true
    }

    private fun allowInbound(signer: String): Boolean {
        val now = clock()
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
        val now = clock()
        signatureAttemptTimes.removeAll { now - it >= 60_000 }
        if (signatureAttemptTimes.size >= SIGNATURE_ATTEMPTS_PER_MINUTE) return false
        signatureAttemptTimes += now
        return true
    }

    private fun closeSubscriptions() {
        relayManager?.unsubscribe(BRIDGE_SUBSCRIPTION)
        subscribedCells = emptySet()
    }

    private fun isOwnEvent(event: NostrEvent, cell: String): Boolean =
        runCatching {
            NostrIdentityBridge.deriveBridgeIdentity(cell, requireContext())
                .publicKeyHex.equals(event.pubkey, ignoreCase = true)
        }.getOrDefault(false)

    private fun isFresh(event: NostrEvent): Boolean =
        abs(clock() - event.createdAt * 1000L) <= MAX_EVENT_AGE_MS

    private fun isValidGeohash(value: String): Boolean =
        value.length in 1..12 &&
            value.matches(Regex("^[0123456789bcdefghjkmnpqrstuvwxyz]+$", RegexOption.IGNORE_CASE))

    private fun NostrEvent.tagValue(name: String): String? =
        tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)

    private fun currentMesh(): MeshService? = meshProvider()

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

}
