package com.bitchat.android.services.meshgraph

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Maintains an internal undirected graph of the mesh based on gossip.
 * Nodes are peers (peerID), edges are direct connections.
 */
class MeshGraphService private constructor() {
    data class GraphNode(val peerID: String, val nickname: String?)
    data class GraphEdge(val a: String, val b: String)
    data class GraphSnapshot(val nodes: List<GraphNode>, val edges: List<GraphEdge>)

    // Map peerID -> nickname (may be null if unknown)
    private val nicknames = ConcurrentHashMap<String, String?>()
    // Adjacency (undirected): peerID -> set of neighbor peerIDs
    private val adjacency = ConcurrentHashMap<String, MutableSet<String>>()
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

            // If no neighbors TLV present, do not modify edges or timestamps
            if (neighborsOrNull == null) {
                publishSnapshot()
                return
            }

            // Newer-only replacement per origin (based on TLV-bearing announcements only)
            val prevTs = lastUpdate[originPeerID]
            if (prevTs != null && prevTs >= timestamp) {
                // Older or equal TLV-bearing update: ignore
                return
            }
            lastUpdate[originPeerID] = timestamp

            // Remove old symmetric edges contributed by this origin
            val prevNeighbors = adjacency[originPeerID]?.toSet().orEmpty()
            prevNeighbors.forEach { n ->
                adjacency[n]?.remove(originPeerID)
            }

            // Replace origin's adjacency with new set (may be empty)
            val newSet = neighborsOrNull.distinct().take(10).filter { it != originPeerID }.toMutableSet()
            adjacency[originPeerID] = newSet
            // Ensure undirected edges
            newSet.forEach { n ->
                adjacency.putIfAbsent(n, mutableSetOf())
                adjacency[n]?.add(originPeerID)
            }

            publishSnapshot()
        }
    }

    fun updateNickname(peerID: String, nickname: String?) {
        if (nickname == null) return
        nicknames[peerID] = nickname
        publishSnapshot()
    }

    private fun publishSnapshot() {
        val nodes = mutableSetOf<String>()
        adjacency.forEach { (a, neighbors) ->
            nodes.add(a)
            nodes.addAll(neighbors)
        }
        // Merge in nicknames-only nodes
        nodes.addAll(nicknames.keys)

        val nodeList = nodes.map { GraphNode(it, nicknames[it]) }.sortedBy { it.peerID }
        val edgeSet = mutableSetOf<Pair<String, String>>()
        adjacency.forEach { (a, ns) ->
            ns.forEach { b ->
                val (x, y) = if (a <= b) a to b else b to a
                edgeSet.add(x to y)
            }
        }
        val edges = edgeSet.map { GraphEdge(it.first, it.second) }.sortedWith(compareBy({ it.a }, { it.b }))
        _graphState.value = GraphSnapshot(nodeList, edges)
    }

    companion object {
        @Volatile private var INSTANCE: MeshGraphService? = null
        fun getInstance(): MeshGraphService = INSTANCE ?: synchronized(this) {
            INSTANCE ?: MeshGraphService().also { INSTANCE = it }
        }
    }
}
