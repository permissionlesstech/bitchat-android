package com.bitchat.android.services.meshgraph

import android.util.Log
import java.util.ArrayDeque

/**
 * Computes shortest paths on the current mesh graph snapshot using BFS
 * (optimal for unit edge weights without PriorityQueue / Pair boxing).
 */
object RoutePlanner {
    private const val TAG = "RoutePlanner"

    /**
     * Return full path [src, ..., dst] if reachable on [meshGraph]'s current snapshot, else null.
     */
    fun shortestPath(src: String, dst: String, meshGraph: MeshGraphService): List<String>? {
        if (src == dst) return listOf(src)
        val snapshot = meshGraph.graphState.value
        val neighbors = HashMap<String, MutableList<String>>(snapshot.nodes.size.coerceAtLeast(4))

        // Only consider confirmed edges for routing
        for (e in snapshot.edges) {
            if (!e.isConfirmed) continue
            neighbors.getOrPut(e.a) { ArrayList(4) }.add(e.b)
            neighbors.getOrPut(e.b) { ArrayList(4) }.add(e.a)
        }

        if (!neighbors.containsKey(src) || !neighbors.containsKey(dst)) return null

        val prev = HashMap<String, String?>()
        val queue = ArrayDeque<String>()
        prev[src] = null
        queue.addLast(src)

        var found = false
        while (queue.isNotEmpty()) {
            val u = queue.removeFirst()
            val adj = neighbors[u] ?: continue
            for (v in adj) {
                if (!prev.containsKey(v)) {
                    prev[v] = u
                    if (v == dst) {
                        found = true
                        break
                    }
                    queue.addLast(v)
                }
            }
            if (found) break
        }

        if (!found) return null

        val path = ArrayList<String>()
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
