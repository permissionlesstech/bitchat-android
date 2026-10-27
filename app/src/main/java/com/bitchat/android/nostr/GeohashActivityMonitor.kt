package com.bitchat.android.nostr

import android.content.Context
import com.bitchat.android.geohash.Geohash
import com.bitchat.android.ui.DataManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Watches worldwide geohash chat traffic while the globe picker is open.
 *
 * Subscribes to kind 20000 (last hour) and kind 20001 (last five minutes) with no `g` filter,
 * so the request says nothing about where the user is. Relays are a fixed worldwide spread plus
 * the few nearest to wherever the user parks the globe, mirroring bitmap.lat's relay plan.
 * Everything is torn down in [stop]; relays this monitor opened are released again.
 */
class GeohashActivityMonitor(private val context: Context) {

    enum class Status { CONNECTING, LIVE, OFFLINE }

    private val relayManager = NostrRelayManager.getInstance(context.applicationContext)
    private val aggregator = GeohashActivityAggregator()

    private val _snapshot = MutableStateFlow(GeohashActivitySnapshot.EMPTY)
    val snapshot: StateFlow<GeohashActivitySnapshot> = _snapshot.asStateFlow()

    private val _status = MutableStateFlow(Status.CONNECTING)
    val status: StateFlow<Status> = _status.asStateFlow()

    private var scope: CoroutineScope? = null
    private var inbox: Channel<NostrEvent>? = null
    private val subscriptionIds = mutableListOf<String>()
    private val subscribedRelays = mutableSetOf<String>()
    private val openedRelays = mutableSetOf<String>()
    private var focusJob: Job? = null
    private var lastFocusCell: String? = null
    /** Confined to the monitor's single-threaded scope. */
    private var dirty = false
    /** Blocked identities, hidden from counts exactly as the channel sheet hides them. */
    private var blocked: Set<String> = emptySet()

    fun start() {
        if (scope != null) return
        // One thread owns the aggregator, so ingest and the publish tick never race.
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        val events = Channel<NostrEvent>(INBOX_CAPACITY, BufferOverflow.DROP_OLDEST)
        scope = newScope
        inbox = events
        _status.value = Status.CONNECTING

        newScope.launch {
            blocked = DataManager(context.applicationContext)
                .apply { loadGeohashBlockedUsers() }
                .geohashBlockedUsers
                .mapTo(HashSet()) { it.lowercase() }
            for (event in events) {
                if (ingest(event, System.currentTimeMillis())) dirty = true
            }
        }
        newScope.launch { publishLoop() }
        newScope.launch {
            delay(OFFLINE_GRACE_MS)
            if (_status.value == Status.CONNECTING) {
                // Connected but nothing to report is still a live, quiet world.
                _status.value = if (relayManager.isConnected.value) Status.LIVE else Status.OFFLINE
            }
        }
        subscribe(sweepRelays())
        lastFocusCell?.let { cell -> subscribe(focusRelays(cell)) }
    }

    fun stop() {
        val current = scope ?: return
        scope = null
        focusJob = null
        current.cancel()
        inbox?.close()
        inbox = null
        subscriptionIds.forEach(relayManager::unsubscribe)
        subscriptionIds.clear()
        subscribedRelays.clear()
        relayManager.releaseRelays(openedRelays)
        openedRelays.clear()
    }

    /**
     * Tells the monitor where the globe is resting. After a short settle it adds the relays
     * nearest that region, so quiet local channels show up as well as the busy hubs.
     */
    fun focusOn(latitude: Double, longitude: Double) {
        val cell = Geohash.encode(latitude, longitude, FOCUS_PRECISION)
        if (cell == lastFocusCell) return
        lastFocusCell = cell
        val current = scope ?: return
        focusJob?.cancel()
        focusJob = current.launch {
            delay(FOCUS_SETTLE_MS)
            launch(Dispatchers.Main) { subscribe(focusRelays(cell)) }
        }
    }

    private fun sweepRelays(): List<String> {
        val nearestPerAnchor = SWEEP_ANCHORS.map { RelayDirectory.closestRelaysForGeohash(it, RELAYS_PER_ANCHOR) }
        val ordered = LinkedHashSet(NostrRelayManager.defaultRelays())
        for (rank in 0 until RELAYS_PER_ANCHOR) {
            nearestPerAnchor.forEach { relays -> relays.getOrNull(rank)?.let(ordered::add) }
        }
        return ordered.take(MAX_SWEEP_RELAYS)
    }

    private fun focusRelays(cell: String): List<String> {
        val room = MAX_TOTAL_RELAYS - subscribedRelays.size
        if (room <= 0) return emptyList()
        return RelayDirectory.closestRelaysForGeohash(cell, RELAYS_PER_FOCUS)
            .filter { it !in subscribedRelays }
            .take(room)
    }

    private fun subscribe(relays: List<String>) {
        if (scope == null) return
        val fresh = relays.filter { it !in subscribedRelays }
        if (fresh.isEmpty()) return
        val alreadyKnown = relayManager.relays.value.mapTo(HashSet()) { it.url }
        openedRelays.addAll(fresh.filter { it !in alreadyKnown })
        subscribedRelays.addAll(fresh)
        relayManager.connectToRelays(fresh)

        val now = System.currentTimeMillis()
        val handler: (NostrEvent) -> Unit = { event -> inbox?.trySend(event) }
        listOf(
            NostrFilter(
                kinds = listOf(NostrKind.EPHEMERAL_EVENT),
                since = ((now / 1000) - GeohashActivityAggregator.CHAT_WINDOW_SECONDS).toInt(),
                limit = REQUEST_LIMIT
            ),
            NostrFilter(
                kinds = listOf(NostrKind.GEOHASH_PRESENCE),
                since = ((now / 1000) - GeohashActivityAggregator.PRESENCE_WINDOW_SECONDS).toInt(),
                limit = REQUEST_LIMIT
            )
        ).forEach { filter ->
            subscriptionIds += relayManager.subscribe(
                filter = filter,
                id = "globe-activity-${UUID.randomUUID()}",
                handler = handler,
                targetRelayUrls = fresh,
                observeOnly = true
            )
        }
    }

    private suspend fun CoroutineScope.publishLoop() {
        var lastPrune = System.currentTimeMillis()
        while (isActive) {
            delay(PUBLISH_INTERVAL_MS)
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastPrune >= PRUNE_INTERVAL_MS) {
                if (aggregator.prune(nowMs)) dirty = true
                lastPrune = nowMs
            }
            if (!dirty) continue
            dirty = false
            val hidden = blocked
            _snapshot.value = aggregator.snapshot(nowMs) { it in hidden }
            _status.value = Status.LIVE
        }
    }

    private fun ingest(event: NostrEvent, nowMillis: Long): Boolean {
        val geohash = event.tags.firstOrNull { it.size >= 2 && it[0] == "g" }?.get(1) ?: return false
        val isChat = event.kind == NostrKind.EPHEMERAL_EVENT && event.content.isNotBlank()
        // Same proof-of-work gate the chat applies, so the sheet and globe see the same events.
        if (event.kind == NostrKind.EPHEMERAL_EVENT) {
            val pow = PoWPreferenceManager.getCurrentSettings()
            if (pow.enabled && pow.difficulty > 0 && !NostrProofOfWork.validateDifficulty(event, pow.difficulty)) {
                return false
            }
        }
        // Refreshing a known person's timestamp needs no proof; adding anyone or anything does.
        if (aggregator.wouldGrow(geohash, event.pubkey, isChat, event.id) && !event.isValidSignature()) {
            return false
        }
        return aggregator.record(
            kind = event.kind,
            eventId = event.id,
            pubkey = event.pubkey,
            geohash = geohash,
            createdAtMillis = event.createdAt * 1000L,
            content = event.content,
            nowMillis = nowMillis
        )
    }

    private companion object {
        /** Synthetic population hubs used only to spread relays around the world. */
        val SWEEP_ANCHORS = listOf(
            "9q", "dr", "dn", "9g", "d5", "6g", "69", "gc", "u0", "u3", "uc", "sr",
            "sx", "st", "s1", "kz", "tt", "te", "w2", "wx", "xn", "r3"
        )
        const val RELAYS_PER_ANCHOR = 2
        const val RELAYS_PER_FOCUS = 3
        const val MAX_SWEEP_RELAYS = 24
        const val MAX_TOTAL_RELAYS = 36
        const val FOCUS_PRECISION = 3
        const val FOCUS_SETTLE_MS = 1_200L
        const val REQUEST_LIMIT = 500
        const val INBOX_CAPACITY = 4_096
        const val PUBLISH_INTERVAL_MS = 500L
        const val PRUNE_INTERVAL_MS = 15_000L
        const val OFFLINE_GRACE_MS = 8_000L
    }
}
