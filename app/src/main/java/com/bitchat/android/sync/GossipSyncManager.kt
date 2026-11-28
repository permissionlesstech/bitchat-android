package com.bitchat.android.sync

import android.util.Log
import com.bitchat.android.mesh.MeshPacketUtils
import com.bitchat.android.mesh.PowerManager
import com.bitchat.android.model.RequestSyncPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import com.bitchat.android.util.AppConstants
import com.bitchat.android.util.hexNibbleOrNegative
import com.bitchat.android.util.toHexString
import kotlin.math.floor
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex

/**
 * Gossip-based synchronization manager using on-demand GCS filters.
 * Tracks seen public packets (ANNOUNCE, broadcast MESSAGE) and periodically requests sync
 * from neighbors. Responds to REQUEST_SYNC by sending missing packets.
 */
class GossipSyncManager(
    private val myPeerID: String,
    private val scope: CoroutineScope,
    private val configProvider: ConfigProvider,
    private val hasPeersProvider: () -> Boolean = { true },
    private val syncIntervalProvider: () -> Long = { DEFAULT_SYNC_INTERVAL_MS },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val syncIntervalFlow: Flow<Long>? = null,
    private val monotonicClockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val wallClockMs: () -> Long = { System.currentTimeMillis() },
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    interface Delegate {
        fun sendPacket(packet: BitchatPacket)
        fun sendPacketToPeer(peerID: String, packet: BitchatPacket)
        fun signPacketForBroadcast(packet: BitchatPacket): BitchatPacket
    }

    interface ConfigProvider {
        fun seenCapacity(): Int // max packets we sync per request (cap across types)
        fun gcsMaxBytes(): Int
        fun gcsTargetFpr(): Double // percent -> 0.0..1.0
    }

    companion object {
        private const val TAG = "GossipSyncManager"
        const val DEFAULT_SYNC_INTERVAL_MS: Long = 30_000L
        const val FOREGROUND_LOW_BATTERY_SYNC_INTERVAL_MS: Long = 60_000L

        /**
         * Keep foreground missed-message recovery responsive (30 s default; 60 s under
         * foreground low-battery modes) and reserve 120–300 s scaling for background operation.
         */
        fun recommendedSyncIntervalMs(profile: PowerManager.RuntimePerformanceProfile): Long =
            when {
                profile.isBackground && profile.mode == PowerManager.PowerMode.ULTRA_LOW_POWER -> 300_000L
                profile.isBackground && profile.mode == PowerManager.PowerMode.POWER_SAVER -> 180_000L
                profile.isBackground -> 120_000L
                profile.mode == PowerManager.PowerMode.POWER_SAVER ||
                    profile.mode == PowerManager.PowerMode.ULTRA_LOW_POWER -> FOREGROUND_LOW_BATTERY_SYNC_INTERVAL_MS
                else -> DEFAULT_SYNC_INTERVAL_MS
            }
    }

    var delegate: Delegate? = null

    private val myPeerIDBytes: ByteArray = MeshPacketUtils.hexStringToByteArray(myPeerID)

    // Defaults (configurable constants)
    private val defaultMaxBytes = SyncDefaults.DEFAULT_FILTER_BYTES
    private val defaultFpr = SyncDefaults.DEFAULT_FPR_PERCENT

    // Stored packets for sync:
    // - broadcast messages: keep up to seenCapacity() most recent, keyed by packetId
    private val messages = LinkedHashMap<String, BitchatPacket>()
    // - announcements: only keep latest per sender peerID
    private val latestAnnouncementByPeer = ConcurrentHashMap<String, Pair<String, BitchatPacket>>()

    // Snapshot & GCS payload cache invalidated on any packet/config change
    private val stateVersion = AtomicLong(0L)
    private val snapshotCacheLock = Any()
    private var cachedSnapshotVersion: Long = -1L
    private var cachedSnapshotExpiresAtWallMs: Long = Long.MAX_VALUE
    private var cachedCap: Int = -1
    private var cachedMaxBytes: Int = -1
    private var cachedFpr: Double = Double.NaN
    private var cachedSnapshot: SyncSnapshot? = null
    private var cachedGcsPayload: ByteArray? = null

    private val responseMutex = Mutex()
    private var responseJob: Job? = null

    /**
     * Per-requester token buckets. `requestTokens` bounds how often we decode a peer's filter.
     * `responseTokens` bounds how many packets that peer can pull. Both refill with elapsed time.
     * Keyed only by the transport's ingress link. REQUEST_SYNC is unauthenticated,
     * so a sender id on the wire is free to rotate and is never a budget key.
     */
    private class RequesterBudget(
        var requestTokens: Double,
        var responseTokens: Double,
        var lastRefillMs: Long
    )

    private val requesterBudgets =
        object : LinkedHashMap<String, RequesterBudget>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, RequesterBudget>
            ): Boolean = size > AppConstants.Sync.MAX_TRACKED_REQUESTERS
        }
    private var periodicJob: Job? = null
    private var cleanupJob: Job? = null
    private val cleanupWakeup = Channel<Unit>(Channel.CONFLATED)

    private fun invalidateSnapshotCache(clearReferences: Boolean = false) {
        stateVersion.incrementAndGet()
        if (clearReferences) {
            synchronized(snapshotCacheLock) {
                cachedSnapshotVersion = -1L
                cachedSnapshotExpiresAtWallMs = Long.MAX_VALUE
                cachedSnapshot = null
                cachedGcsPayload = null
            }
        }
    }

    fun start() {
        periodicJob?.cancel()
        var lastTickMonoMs = monotonicClockMs()
        periodicJob = scope.launch(ioDispatcher) {
            val intervalFlow = syncIntervalFlow
            if (intervalFlow != null) {
                intervalFlow.distinctUntilChanged().collectLatest { rawIntervalMs ->
                    val intervalMs = rawIntervalMs.coerceAtLeast(1_000L)
                    while (isActive) {
                        try {
                            // Preserve elapsed time across profile changes instead of restarting
                            // the full timer when returning to the foreground.
                            val elapsedMs = (monotonicClockMs() - lastTickMonoMs).coerceAtLeast(0L)
                            val remainingMs = (intervalMs - elapsedMs).coerceAtLeast(0L)
                            if (remainingMs > 0L) {
                                delay(remainingMs)
                            }
                            lastTickMonoMs = monotonicClockMs()
                            if (hasPeersProvider()) {
                                sendRequestSync()
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { Log.e(TAG, "Periodic sync error: ${e.message}") }
                    }
                }
            } else {
                while (isActive) {
                    try {
                        val intervalMs = syncIntervalProvider().coerceAtLeast(1_000L)
                        delay(intervalMs)
                        lastTickMonoMs = monotonicClockMs()
                        if (hasPeersProvider()) {
                            sendRequestSync()
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { Log.e(TAG, "Periodic sync error: ${e.message}") }
                }
            }
        }

        // Demand-driven cleanup of stale announcements: sleeps with zero wakeups while empty.
        cleanupJob?.cancel()
        cleanupJob = scope.launch(ioDispatcher) {
            while (isActive) {
                try {
                    if (latestAnnouncementByPeer.isEmpty()) {
                        cleanupWakeup.receive()
                        continue
                    }
                    delay(com.bitchat.android.util.AppConstants.Sync.CLEANUP_INTERVAL_MS)
                    pruneStaleAnnouncements()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { Log.e(TAG, "Periodic cleanup error: ${e.message}") }
            }
        }
    }

    @Synchronized
    fun stop() {
        responseJob?.cancel(); responseJob = null
        periodicJob?.cancel(); periodicJob = null
        cleanupJob?.cancel(); cleanupJob = null
    }

    @Synchronized
    fun clear() {
        responseJob?.cancel(); responseJob = null
        synchronized(messages) {
            messages.clear()
        }
        latestAnnouncementByPeer.clear()
        synchronized(requesterBudgets) {
            requesterBudgets.clear()
        }
        invalidateSnapshotCache(clearReferences = true)
        Log.d(TAG, "Cleared all gossip sync messages and announcements")
    }

    fun scheduleInitialSync(delayMs: Long = 5_000L) {
        scope.launch(Dispatchers.IO) {
            delay(delayMs)
            if (hasPeersProvider()) {
                sendRequestSync()
            }
        }
    }

    fun scheduleInitialSyncToPeer(peerID: String, delayMs: Long = 5_000L) {
        scope.launch(Dispatchers.IO) {
            delay(delayMs)
            sendRequestSyncToPeer(peerID)
        }
    }

    fun onPublicPacketSeen(packet: BitchatPacket) {
        // Only ANNOUNCE or broadcast MESSAGE
        val mt = MessageType.fromValue(packet.type)
        val isBroadcastMessage = (mt == MessageType.MESSAGE && (packet.recipientID == null || packet.recipientID.contentEquals(SpecialRecipients.BROADCAST)))
        val isAnnouncement = (mt == MessageType.ANNOUNCE)
        if (!isBroadcastMessage && !isAnnouncement) return

        val idBytes = PacketIdUtil.computeIdBytes(packet)
        val id = idBytes.toHexString()

        if (isBroadcastMessage) {
            synchronized(messages) {
                messages[id] = packet
                // Enforce capacity (remove oldest when exceeded)
                val cap = configProvider.seenCapacity().coerceAtLeast(1)
                while (messages.size > cap) {
                    val it = messages.entries.iterator()
                    if (it.hasNext()) { it.next(); it.remove() } else break
                }
            }
            invalidateSnapshotCache()
        } else if (isAnnouncement) {
            // Ignore stale announcements older than STALE_PEER_TIMEOUT
            val now = wallClockMs()
            val age = now - packet.timestamp.toLong()
            if (age > com.bitchat.android.util.AppConstants.Mesh.STALE_PEER_TIMEOUT_MS) {
                Log.d(TAG, "Ignoring stale ANNOUNCE (age=${age}ms > ${com.bitchat.android.util.AppConstants.Mesh.STALE_PEER_TIMEOUT_MS}ms)")
                return
            }
            // senderID is fixed-size 8 bytes; map to hex string for key
            val sender = packet.senderID.toHexString()
            latestAnnouncementByPeer[sender] = id to packet
            // Enforce capacity (remove oldest when exceeded)
            val cap = configProvider.seenCapacity().coerceAtLeast(1)
            while (latestAnnouncementByPeer.size > cap) {
                val it = latestAnnouncementByPeer.entries.iterator()
                if (it.hasNext()) { it.next(); it.remove() } else break
            }
            invalidateSnapshotCache()
            cleanupWakeup.trySend(Unit)
        }
    }

    private fun sendRequestSync() {
        val payload = buildGcsPayload()

        val packet = BitchatPacket(
            type = MessageType.REQUEST_SYNC.value,
            senderID = myPeerIDBytes,
            timestamp = System.currentTimeMillis().toULong(),
            payload = payload,
            ttl = com.bitchat.android.util.AppConstants.SYNC_TTL_HOPS // neighbors only
        )
        // Sign and broadcast
        val signed = delegate?.signPacketForBroadcast(packet) ?: packet
        delegate?.sendPacket(signed)
    }

    private fun sendRequestSyncToPeer(peerID: String) {
        val payload = buildGcsPayload()

        val packet = BitchatPacket(
            type = MessageType.REQUEST_SYNC.value,
            senderID = myPeerIDBytes,
            recipientID = MeshPacketUtils.hexStringToByteArray(peerID),
            timestamp = System.currentTimeMillis().toULong(),
            payload = payload,
            ttl = com.bitchat.android.util.AppConstants.SYNC_TTL_HOPS // neighbor only
        )
        Log.d(TAG, "Sending sync request to $peerID (${payload.size} bytes)")
        // Sign and send directly to peer
        val signed = delegate?.signPacketForBroadcast(packet) ?: packet
        delegate?.sendPacketToPeer(peerID, signed)
    }

    /**
     * Charges [budgetKey] for one request and returns how many packets may be sent, or 0 when the
     * request must be dropped. Called before any decoding or hashing.
     */
    private fun admitRequest(budgetKey: String): Int {
        val capacity = try {
            configProvider.seenCapacity()
        } catch (_: Exception) {
            0
        }.coerceAtLeast(1).toDouble()
        val now = nowMs()

        synchronized(requesterBudgets) {
            val budget = requesterBudgets.getOrPut(budgetKey) {
                RequesterBudget(
                    requestTokens = AppConstants.Sync.REQUEST_BURST.toDouble(),
                    responseTokens = capacity,
                    lastRefillMs = now
                )
            }

            val elapsedMs = (now - budget.lastRefillMs).coerceAtLeast(0L).toDouble()
            budget.lastRefillMs = now
            budget.requestTokens = (
                budget.requestTokens + elapsedMs / AppConstants.Sync.REQUEST_INTERVAL_MS
                ).coerceAtMost(AppConstants.Sync.REQUEST_BURST.toDouble())
            budget.responseTokens = (
                budget.responseTokens +
                    capacity * elapsedMs / AppConstants.Sync.RESPONSE_REFILL_WINDOW_MS
                ).coerceAtMost(capacity)

            if (budget.requestTokens < 1.0) return 0
            val allowance = floor(budget.responseTokens).toInt()
            if (allowance <= 0) return 0

            budget.requestTokens -= 1.0
            return allowance
        }
    }

    internal fun trackedRequesterCount(): Int = synchronized(requesterBudgets) {
        requesterBudgets.size
    }

    private fun chargeResponses(budgetKey: String, sent: Int) {
        if (sent <= 0) return
        synchronized(requesterBudgets) {
            val budget = requesterBudgets[budgetKey] ?: return
            budget.responseTokens = (budget.responseTokens - sent).coerceAtLeast(0.0)
        }
    }

    private fun refundRequest(budgetKey: String) {
        synchronized(requesterBudgets) {
            val budget = requesterBudgets[budgetKey] ?: return
            budget.requestTokens = (budget.requestTokens + 1.0)
                .coerceAtMost(AppConstants.Sync.REQUEST_BURST.toDouble())
        }
    }

    /**
     * @param ingressLinkID locally-assigned identity of the link the request arrived on. The
     *   sender id is unauthenticated, so budgeting by it would let a peer mint a fresh allowance
     *   per request.
     */
    @Synchronized
    fun handleRequestSync(
        fromPeerID: String,
        request: RequestSyncPacket,
        ingressLinkID: String? = null
    ) {
        val budgetKey = ingressLinkID
        if (budgetKey == null) {
            Log.d(TAG, "Dropping REQUEST_SYNC from $fromPeerID: no ingress link")
            return
        }
        val allowance = admitRequest(budgetKey)
        if (allowance <= 0) {
            Log.d(TAG, "Dropping REQUEST_SYNC from $fromPeerID: over budget")
            return
        }
        if (!responseMutex.tryLock()) {
            refundRequest(budgetKey)
            return
        }

        val sorted = GCSFilter.decodeToSortedSet(request.p, request.m, request.data)
        fun mightContain(id: ByteArray): Boolean {
            val v = GCSFilter.h64(id) % request.m
            val nonZeroV = if (v == 0L) 1L else v
            return GCSFilter.contains(sorted, nonZeroV)
        }

        responseJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var sent = 0
            try {
                val missing = buildSyncSnapshot().packets
                    .filter { (id, _) -> !mightContain(hexToBytes(id)) }
                    .sortedWith(compareBy<Pair<String, BitchatPacket>> {
                        if (it.second.type == MessageType.ANNOUNCE.value) 0 else 1
                    }.thenBy { it.second.timestamp }.thenBy { it.first })
                    .take(allowance)
                missing.forEachIndexed { index, (_, packet) ->
                    if (index > 0) delay(20L)
                    ensureActive()
                    delegate?.sendPacketToPeer(
                        fromPeerID,
                        packet.copy(ttl = com.bitchat.android.util.AppConstants.SYNC_TTL_HOPS)
                    )
                    sent++
                }
            } finally {
                chargeResponses(budgetKey, sent)
                responseMutex.unlock()
            }
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = if (hex.length % 2 == 0) hex else "0$hex"
        val out = ByteArray(clean.length ushr 1)
        var i = 0
        var pos = 0
        while (pos < clean.length) {
            val hi = hexNibbleOrNegative(clean[pos]).coerceAtLeast(0)
            val lo = hexNibbleOrNegative(clean[pos + 1]).coerceAtLeast(0)
            out[i++] = ((hi shl 4) or lo).toByte()
            pos += 2
        }
        return out
    }

    private data class SyncSnapshot(
        val packets: List<Pair<String, BitchatPacket>>,
        val filter: GCSFilter.Params
    )

    private fun buildSyncSnapshot(): SyncSnapshot {
        val maxBytes = try { configProvider.gcsMaxBytes() } catch (_: Exception) { defaultMaxBytes }
        val fpr = try { configProvider.gcsTargetFpr() } catch (_: Exception) { defaultFpr }
        val cap = configProvider.seenCapacity().coerceAtLeast(1)
        val now = wallClockMs()
        val version = stateVersion.get()

        synchronized(snapshotCacheLock) {
            val existing = cachedSnapshot
            if (existing != null &&
                cachedSnapshotVersion == version &&
                now <= cachedSnapshotExpiresAtWallMs &&
                cachedCap == cap &&
                cachedMaxBytes == maxBytes &&
                cachedFpr == fpr
            ) {
                return existing
            }
        }

        val maxAnnouncementAgeMs = com.bitchat.android.util.AppConstants.Mesh.STALE_PEER_TIMEOUT_MS
        var earliestExpiryWallMs = Long.MAX_VALUE
        val announcements = HashMap<String, Pair<String, BitchatPacket>>()
        for ((peer, pair) in latestAnnouncementByPeer.entries) {
            val ts = pair.second.timestamp.toLong()
            if (now - ts <= maxAnnouncementAgeMs) {
                announcements[peer] = pair
                val expiresAt = ts + maxAnnouncementAgeMs
                if (expiresAt < earliestExpiryWallMs) {
                    earliestExpiryWallMs = expiresAt
                }
            }
        }
        val candidates = announcements.values.toMutableList()
        synchronized(messages) {
            candidates.addAll(messages.entries.map { it.key to it.value })
        }
        // Stable tie-breaking makes equal caches advertise the same window on different nodes.
        candidates.sortWith(compareByDescending<Pair<String, BitchatPacket>> { it.second.timestamp }
            .thenBy { it.first })
        val limit = minOf(cap, GCSFilter.estimateMaxElementsForSize(maxBytes, GCSFilter.deriveP(fpr)))
        val selectedById = linkedMapOf<String, BitchatPacket>()
        for ((id, packet) in candidates) {
            if (id in selectedById) continue
            val announcement = if (packet.type == MessageType.MESSAGE.value) {
                val sender = packet.senderID.toHexString()
                announcements[sender]?.takeUnless { it.first in selectedById }
            } else null
            val required = if (announcement == null) 1 else 2
            if (selectedById.size + required > limit) continue
            // Keep every prefix dependency-closed: filter encoding may trim its tail.
            if (announcement != null) selectedById[announcement.first] = announcement.second
            selectedById[id] = packet
        }
        val selected = selectedById.entries.map { it.key to it.value }
        val filter = GCSFilter.buildFilter(selected.map { hexToBytes(it.first) }, maxBytes, fpr)
        // buildFilter can trim further to meet its byte budget. Offer exactly what it encoded.
        val encodedCount = (filter.m ushr filter.p).toInt().coerceIn(0, selected.size)
        val snapshot = SyncSnapshot(selected.take(encodedCount), filter)

        synchronized(snapshotCacheLock) {
            if (stateVersion.get() == version) {
                cachedSnapshotVersion = version
                cachedSnapshotExpiresAtWallMs = earliestExpiryWallMs
                cachedCap = cap
                cachedMaxBytes = maxBytes
                cachedFpr = fpr
                cachedSnapshot = snapshot
                cachedGcsPayload = null
            }
        }
        return snapshot
    }

    private fun buildGcsPayload(): ByteArray {
        val snapshot = buildSyncSnapshot()
        synchronized(snapshotCacheLock) {
            if (cachedSnapshot === snapshot) {
                cachedGcsPayload?.let { return it }
            }
        }
        val params = snapshot.filter
        val encoded = RequestSyncPacket(p = params.p, m = params.m.coerceAtLeast(1), data = params.data).encode()
        synchronized(snapshotCacheLock) {
            if (cachedSnapshot === snapshot) {
                cachedGcsPayload = encoded
            }
        }
        return encoded
    }

    // Periodically remove stale announcements and all their messages
    private fun pruneStaleAnnouncements() {
        val now = wallClockMs()
        val stalePeers = mutableListOf<String>()

        // Identify stale announcements by age
        for ((peerID, pair) in latestAnnouncementByPeer.entries) {
            val pkt = pair.second
            val age = now - pkt.timestamp.toLong()
            if (age > com.bitchat.android.util.AppConstants.Mesh.STALE_PEER_TIMEOUT_MS) {
                stalePeers.add(peerID)
            }
        }

        if (stalePeers.isEmpty()) return

        // Remove announcements and their messages
        var totalPrunedMsgs = 0
        for (peerID in stalePeers) {
            // Count messages to be pruned for logging
            val toRemove = mutableListOf<String>()
            synchronized(messages) {
                for ((id, message) in messages) {
                    val sender = message.senderID.toHexString()
                    if (sender == peerID) toRemove.add(id)
                }
            }
            totalPrunedMsgs += toRemove.size

            // Reuse existing removal which also clears announcement entry
            removeAnnouncementForPeer(peerID)
        }

        Log.d(TAG, "Pruned ${stalePeers.size} stale announcements and $totalPrunedMsgs messages")
    }

    // Explicitly remove stored announcement for a given peer (hex ID)
    fun removeAnnouncementForPeer(peerID: String) {
        val key = peerID.lowercase()
        val removedAnnouncement = latestAnnouncementByPeer.remove(key) != null
        if (removedAnnouncement) {
            Log.d(TAG, "Removed stored announcement for peer $peerID")
        }

        // Collect IDs to remove first to avoid modifying collection while iterating
        val idsToRemove = mutableListOf<String>()
        synchronized(messages) {
            for ((id, message) in messages) {
                val sender = message.senderID.toHexString()
                if (sender == key) {
                    idsToRemove.add(id)
                }
            }
            for (id in idsToRemove) {
                messages.remove(id)
            }
        }
        
        if (removedAnnouncement || idsToRemove.isNotEmpty()) {
            invalidateSnapshotCache()
        }
        if (idsToRemove.isNotEmpty()) {
            Log.d(TAG, "Pruned ${idsToRemove.size} messages with senders without announcements")
        }
    }
}
