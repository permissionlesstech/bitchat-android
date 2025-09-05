package com.bitchat.android.services.meshgraph

import android.util.Log
import java.util.PriorityQueue

/**
 * Computes shortest paths on the current mesh graph snapshot using Dijkstra.
 * Assumes unit edge weights.
 */
object RoutePlanner {
    private const val TAG = "RoutePlanner"

    /**
     * Return full path [src, ..., dst] if reachable, else null.
     */
    fun shortestPath(src: String, dst: String): List<String>? {
        if (src == dst) return listOf(src)
        val snapshot = MeshGraphService.getInstance().graphState.value
        val neighbors = mutableMapOf<String, MutableSet<String>>()
        snapshot.edges.forEach { e ->
            neighbors.getOrPut(e.a) { mutableSetOf() }.add(e.b)
            neighbors.getOrPut(e.b) { mutableSetOf() }.add(e.a)
        }
        // Ensure nodes known even if isolated
        snapshot.nodes.forEach { n -> neighbors.putIfAbsent(n.peerID, mutableSetOf()) }

        if (!neighbors.containsKey(src) || !neighbors.containsKey(dst)) return null

        val dist = mutableMapOf<String, Int>()
        val prev = mutableMapOf<String, String?>()
        val pq = PriorityQueue<Pair<String, Int>>(compareBy { it.second })

        neighbors.keys.forEach { v ->
            dist[v] = if (v == src) 0 else Int.MAX_VALUE
            prev[v] = null
        }
        pq.add(src to 0)

        while (pq.isNotEmpty()) {
            val (u, d) = pq.poll()
            if (d > (dist[u] ?: Int.MAX_VALUE)) continue
            if (u == dst) break
            neighbors[u]?.forEach { v ->
                val alt = d + 1
                if (alt < (dist[v] ?: Int.MAX_VALUE)) {
                    dist[v] = alt
                    prev[v] = u
                    pq.add(v to alt)
                }
            }
        }

        if ((dist[dst] ?: Int.MAX_VALUE) == Int.MAX_VALUE) return null

        val path = mutableListOf<String>()
        var cur: String? = dst
        while (cur != null) {
            path.add(cur)
            cur = prev[cur]
        }
        path.reverse()
        Log.d(TAG, "Computed path $path")
        return path
    }
}

