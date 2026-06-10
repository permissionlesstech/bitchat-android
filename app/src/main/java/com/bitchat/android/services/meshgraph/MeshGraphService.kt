package com.bitchat.android.services.meshgraph

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Maintains an internal graph of the mesh based on gossip.
 * Nodes are peers (peerID), edges are direct connections.
 */
class MeshGraphService private constructor() {
    data class GraphNode(val peerID: String, val nickname: String?)
    data class GraphEdge(val a: String, val b: String, val isConfirmed: Boolean, val confirmedBy: String? = null)
    data class GraphSnapshot(val nodes: List<GraphNode>, val edges: List<GraphEdge>)

    // Map peerID -> nickname (may be null if unknown)
    private val nicknames = ConcurrentHashMap<String, String?>()
    // Announcements: peerID -> set of neighbor peerIDs that *this* peer claims to see
    private val announcements = ConcurrentHashMap<String, Set<String>>()
    // Latest announcement timestamp per peer (ULong from packet)
    private val lastUpdate = ConcurrentHashMap<String, ULong>()

    private val _graphState = MutableStateFlow(GraphSnapshot(emptyList(), emptyList()))
    val graphState: StateFlow<GraphSnapshot> = _graphState.asStateFlow()

    /**
     * Update graph from a verified announcement.
     * Replaces previous neighbors for origin if this is newer (by timestamp).
     */
    fun updateFromAnnouncement(originPeerID: String, originNickname: String?, neighborsOrNull: List<String>?, timestamp: ULong) {
        synchronized(this) {
            // Always update nickname if provided
            if (originNickname != null) nicknames[originPeerID] = originNickname

            // 1. Check timestamp first to ensure this is the latest word from the peer
            val prevTs = lastUpdate[originPeerID]
            if (prevTs != null && prevTs >= timestamp) {
                // Older or equal update: ignore
                return
            }
            lastUpdate[originPeerID] = timestamp

            // 2. Latest announcement determines state.
            // If neighborsOrNull is null (TLV omitted), it means the peer is not reporting any neighbors (empty list).
            val neighbors = neighborsOrNull ?: emptyList()
            
            // Filter out self-loops just in case
            val newSet = neighbors.distinct().take(10).filter { it != originPeerID }.toSet()
            announcements[originPeerID] = newSet

            publishSnapshot()
        }
    }

    fun updateNickname(peerID: String, nickname: String?) {
        if (nickname == null) return
        nicknames[peerID] = nickname
        publishSnapshot()
    }

    /**
     * Remove a peer from the graph completely (e.g. when stale/offline).
     */
    fun removePeer(peerID: String) {
        synchronized(this) {
            nicknames.remove(peerID)
            announcements.remove(peerID)
            lastUpdate.remove(peerID)
            publishSnapshot()
        }
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
        @Volatile private var INSTANCE: MeshGraphService? = null
        fun getInstance(): MeshGraphService = INSTANCE ?: synchronized(this) {
            INSTANCE ?: MeshGraphService().also { INSTANCE = it }
        }

        @org.jetbrains.annotations.TestOnly
        fun resetForTesting() {
            synchronized(this) {
                INSTANCE = null
            }
        }
    }
}
