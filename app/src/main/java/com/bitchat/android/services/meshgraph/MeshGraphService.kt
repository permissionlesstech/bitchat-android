package com.bitchat.android.services.meshgraph

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Maintains an internal graph of the mesh based on gossip.
 * Nodes are peers (peerID), edges are direct connections.
 */
class MeshGraphService internal constructor(
    private val clockMs: () -> Long = ::defaultMonotonicClockMs
) {
    data class GraphNode(val peerID: String, val nickname: String?)
    data class GraphEdge(val a: String, val b: String, val isConfirmed: Boolean, val confirmedBy: String? = null)
    data class GraphSnapshot(val nodes: List<GraphNode>, val edges: List<GraphEdge>)

    /**
     * Atomic snapshot of the fresh confirmed 2-hop neighborhood around [centerPeerID].
     *
     * [centerIsFresh] indicates whether [centerPeerID] itself has an unexpired announcement.
     * [n1] contains all fresh confirmed 1-hop neighbors of [centerPeerID].
     * [neighborsByN1] maps each `u` in [n1] to all of `u`'s fresh confirmed 1-hop neighbors.
     */
    data class TwoHopNeighborhood(
        val centerPeerID: String,
        val centerIsFresh: Boolean,
        val n1: Set<String>,
        val neighborsByN1: Map<String, Set<String>>
    )

    // Map peerID -> nickname (may be null if unknown)
    private val nicknames = ConcurrentHashMap<String, String?>()
    // Announcements: peerID -> set of neighbor peerIDs that *this* peer claims to see
    private val announcements = ConcurrentHashMap<String, Set<String>>()
    // Latest announcement timestamp per peer (ULong from packet)
    private val lastUpdate = ConcurrentHashMap<String, ULong>()
    // Local monotonic receipt time (ms) when each peer's latest accepted announcement arrived
    private val lastReceivedAtMs = ConcurrentHashMap<String, Long>()

    private val _graphState = MutableStateFlow(GraphSnapshot(emptyList(), emptyList()))
    val graphState: StateFlow<GraphSnapshot> = _graphState.asStateFlow()

    /**
     * Update graph from a verified announcement.
     * Replaces previous neighbors for origin if this is newer (by timestamp).
     *
     * @return `true` if the announcement was accepted and either introduced [originPeerID]
     *   into the graph or changed its reported neighbor set; `false` if ignored as stale/duplicate
     *   or if the reported neighbor set was unchanged.
     */
    fun updateFromAnnouncement(
        originPeerID: String,
        originNickname: String?,
        neighborsOrNull: List<String>?,
        timestamp: ULong
    ): Boolean {
        synchronized(this) {
            // Always update nickname if provided
            if (originNickname != null) nicknames[originPeerID] = originNickname

            // 1. Check timestamp first to ensure this is the latest word from the peer
            val prevTs = lastUpdate[originPeerID]
            if (prevTs != null && prevTs >= timestamp) {
                // Older or equal update: ignore
                return false
            }
            lastUpdate[originPeerID] = timestamp
            lastReceivedAtMs[originPeerID] = clockMs()

            // 2. Latest announcement determines state.
            // If neighborsOrNull is null (TLV omitted), it means the peer is not reporting any neighbors (empty list).
            val neighbors = neighborsOrNull ?: emptyList()

            // Filter out self-loops just in case
            val newSet = neighbors.distinct().take(10).filter { it != originPeerID }.toSet()
            val prevSet = announcements[originPeerID]
            val topologyChanged = prevSet == null || prevSet != newSet
            announcements[originPeerID] = newSet

            publishSnapshot()
            return topologyChanged
        }
    }

    fun updateNickname(peerID: String, nickname: String?) {
        if (nickname == null) return
        nicknames[peerID] = nickname
        publishSnapshot()
    }

    /**
     * Returns true if both [a] and [b] currently report each other in their latest announcements,
     * regardless of announcement age.
     */
    fun isConfirmedNeighbor(a: String, b: String): Boolean {
        if (a == b) return false
        synchronized(this) {
            return announcements[a]?.contains(b) == true && announcements[b]?.contains(a) == true
        }
    }

    /**
     * Returns true if [peerID] has an accepted announcement received within [maxAgeMs] according
     * to the local monotonic clock.
     */
    fun isFreshAnnouncement(peerID: String, maxAgeMs: Long = DEFAULT_EDGE_MAX_AGE_MS): Boolean {
        if (maxAgeMs < 0L) return false
        synchronized(this) {
            return isFreshAtLocked(peerID, clockMs(), maxAgeMs)
        }
    }

    /**
     * Returns true if [a] and [b] mutually confirm each other AND both peers' announcements were
     * received locally within [maxAgeMs].
     */
    fun isFreshConfirmedNeighbor(
        a: String,
        b: String,
        maxAgeMs: Long = DEFAULT_EDGE_MAX_AGE_MS
    ): Boolean {
        if (a == b || maxAgeMs < 0L) return false
        synchronized(this) {
            val now = clockMs()
            return isFreshConfirmedEdgeLocked(a, b, now, maxAgeMs)
        }
    }

    /**
     * Returns all peers that have a fresh, mutually confirmed edge with [peerID].
     */
    fun freshConfirmedNeighborsOf(
        peerID: String,
        maxAgeMs: Long = DEFAULT_EDGE_MAX_AGE_MS
    ): Set<String> {
        if (maxAgeMs < 0L) return emptySet()
        synchronized(this) {
            return freshConfirmedNeighborsOfLocked(peerID, clockMs(), maxAgeMs)
        }
    }

    /**
     * Captures a consistent 2-hop fresh confirmed neighborhood snapshot around [centerPeerID]
     * under a single lock so MPR selection cannot observe torn updates.
     */
    fun freshTwoHopNeighborhood(
        centerPeerID: String,
        maxAgeMs: Long = DEFAULT_EDGE_MAX_AGE_MS
    ): TwoHopNeighborhood {
        if (maxAgeMs < 0L) {
            return TwoHopNeighborhood(centerPeerID, centerIsFresh = false, emptySet(), emptyMap())
        }
        synchronized(this) {
            val now = clockMs()
            val centerIsFresh = isFreshAtLocked(centerPeerID, now, maxAgeMs)
            if (!centerIsFresh) {
                return TwoHopNeighborhood(centerPeerID, centerIsFresh = false, emptySet(), emptyMap())
            }
            val n1 = freshConfirmedNeighborsOfLocked(centerPeerID, now, maxAgeMs)
            val neighborsByN1 = LinkedHashMap<String, Set<String>>(n1.size)
            for (u in n1) {
                neighborsByN1[u] = freshConfirmedNeighborsOfLocked(u, now, maxAgeMs)
            }
            return TwoHopNeighborhood(
                centerPeerID = centerPeerID,
                centerIsFresh = true,
                n1 = n1,
                neighborsByN1 = neighborsByN1
            )
        }
    }

    /**
     * Promptly prune a direct edge between [peerA] and [peerB] (e.g., on link disconnect)
     * without erasing unrelated edges of either peer.
     */
    fun removeDirectEdge(peerA: String, peerB: String) {
        if (peerA == peerB) return
        synchronized(this) {
            var changed = false
            announcements[peerA]?.let { current ->
                if (peerB in current) {
                    announcements[peerA] = current - peerB
                    changed = true
                }
            }
            announcements[peerB]?.let { current ->
                if (peerA in current) {
                    announcements[peerB] = current - peerA
                    changed = true
                }
            }
            if (changed) {
                publishSnapshot()
            }
        }
    }

    /**
     * Remove a peer from the graph completely (e.g. when stale/offline).
     */
    fun removePeer(peerID: String) {
        synchronized(this) {
            nicknames.remove(peerID)
            announcements.remove(peerID)
            lastUpdate.remove(peerID)
            lastReceivedAtMs.remove(peerID)
            announcements.keys.toList().forEach { originPeerID ->
                announcements.computeIfPresent(originPeerID) { _, neighbors ->
                    neighbors - peerID
                }
            }
            publishSnapshot()
        }
    }

    private fun isFreshAtLocked(peerID: String, nowMs: Long, maxAgeMs: Long): Boolean {
        val receivedAt = lastReceivedAtMs[peerID] ?: return false
        val ageMs = nowMs - receivedAt
        return ageMs in 0L..maxAgeMs
    }

    private fun isFreshConfirmedEdgeLocked(
        a: String,
        b: String,
        nowMs: Long,
        maxAgeMs: Long
    ): Boolean {
        if (a == b) return false
        if (!isFreshAtLocked(a, nowMs, maxAgeMs) || !isFreshAtLocked(b, nowMs, maxAgeMs)) {
            return false
        }
        return announcements[a]?.contains(b) == true && announcements[b]?.contains(a) == true
    }

    private fun freshConfirmedNeighborsOfLocked(
        peerID: String,
        nowMs: Long,
        maxAgeMs: Long
    ): Set<String> {
        if (!isFreshAtLocked(peerID, nowMs, maxAgeMs)) return emptySet()
        val declared = announcements[peerID] ?: return emptySet()
        if (declared.isEmpty()) return emptySet()
        val confirmed = LinkedHashSet<String>(declared.size)
        for (neighbor in declared) {
            if (neighbor != peerID &&
                isFreshAtLocked(neighbor, nowMs, maxAgeMs) &&
                announcements[neighbor]?.contains(peerID) == true
            ) {
                confirmed.add(neighbor)
            }
        }
        return confirmed
    }

    private fun publishSnapshot() {
        // Collect all known nodes from nicknames and announcements
        val allNodes = mutableSetOf<String>()
        allNodes.addAll(nicknames.keys)
        announcements.forEach { (origin, neighbors) ->
            allNodes.add(origin)
            allNodes.addAll(neighbors)
        }

        val nodeList = allNodes.map { GraphNode(it, nicknames[it]) }.sortedBy { it.peerID }

        val edges = mutableListOf<GraphEdge>()
        val processedPairs = mutableSetOf<Pair<String, String>>()

        // We only care about connections that exist in at least one direction.
        // So iterating through all entries in `announcements` covers every declared edge.
        announcements.forEach { (source, targets) ->
            targets.forEach { target ->
                val pair = if (source <= target) source to target else target to source
                if (processedPairs.add(pair)) {
                    // This is a new pair we haven't evaluated yet
                    val (a, b) = pair
                    val aAnnouncesB = announcements[a]?.contains(b) == true
                    val bAnnouncesA = announcements[b]?.contains(a) == true

                    if (aAnnouncesB && bAnnouncesA) {
                        edges.add(GraphEdge(a, b, isConfirmed = true))
                    } else if (aAnnouncesB) {
                        edges.add(GraphEdge(a, b, isConfirmed = false, confirmedBy = a))
                    } else if (bAnnouncesA) {
                        edges.add(GraphEdge(a, b, isConfirmed = false, confirmedBy = b))
                    }
                }
            }
        }

        val sortedEdges = edges.sortedWith(compareBy({ it.a }, { it.b }))
        _graphState.value = GraphSnapshot(nodeList, sortedEdges)
    }

    companion object {
        /**
         * Default maximum local age for a peer's announcement to be treated as fresh for
         * MPR selection and neighbor pruning (4x the foreground 30s announce cadence and
         * stricter than the 180s stale-peer timeout).
         */
        const val DEFAULT_EDGE_MAX_AGE_MS: Long = 120_000L

        @Volatile private var INSTANCE: MeshGraphService? = null
        @Volatile private var clockProviderForTests: () -> Long = ::defaultMonotonicClockMs

        internal fun defaultMonotonicClockMs(): Long = try {
            SystemClock.elapsedRealtime()
        } catch (_: Throwable) {
            System.nanoTime() / 1_000_000L
        }

        fun getInstance(): MeshGraphService = INSTANCE ?: synchronized(this) {
            INSTANCE ?: MeshGraphService(clockProviderForTests).also { INSTANCE = it }
        }

        @org.jetbrains.annotations.TestOnly
        fun resetForTesting(clockMs: () -> Long = ::defaultMonotonicClockMs) {
            synchronized(this) {
                clockProviderForTests = clockMs
                INSTANCE = null
            }
        }
    }
}
