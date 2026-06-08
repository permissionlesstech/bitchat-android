package com.bitchat.android.wifiaware

import android.net.ConnectivityManager
import android.util.Log
import com.bitchat.android.mesh.MeshConnectionTracker
import kotlinx.coroutines.CoroutineScope
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks Wi-Fi Aware connections and manages retry logic using the shared state machine.
 */
class WifiAwareConnectionTracker(
    scope: CoroutineScope,
    private val cm: ConnectivityManager
) : MeshConnectionTracker(scope, TAG) {

    companion object {
        private const val TAG = "WifiAwareConnectionTracker"
    }

    // Active resources per peer
    val peerSockets = ConcurrentHashMap<String, SyncedSocket>()
    private val socketAliases = ConcurrentHashMap<String, String>()
    val serverSockets = ConcurrentHashMap<String, ServerSocket>()
    val networkCallbacks = ConcurrentHashMap<String, ConnectivityManager.NetworkCallback>()

    override fun isConnected(id: String): Boolean {
        // We consider it connected if we have a client socket to them
        return getSocketForPeer(id) != null
    }

    override fun disconnect(id: String) {
        Log.d(TAG, "Disconnecting peer $id")
        val canonicalId = resolveCanonicalPeerId(id)
        
        // 1. Close client socket
        peerSockets.remove(canonicalId)?.let {
            try { it.close() } catch (e: Exception) { Log.w(TAG, "Error closing socket for $id: ${e.message}") }
        }
        socketAliases.entries.removeIf { it.key == id || it.key == canonicalId || it.value == canonicalId }

        // 2. Close server socket
        serverSockets.remove(canonicalId)?.let {
            try { it.close() } catch (e: Exception) { Log.w(TAG, "Error closing server socket for $id: ${e.message}") }
        }

        // Ensure any pending/active network request is explicitly released
        releaseNetworkRequest(canonicalId)
    }

    fun releaseNetworkRequest(id: String) {
        if (!networkCallbacks.containsKey(id)) return
        
        // 3. Unregister network callback properly from ConnectivityManager
        networkCallbacks.remove(id)?.let {
            try { 
                Log.d(TAG, "Unregistering network callback for $id")
                cm.unregisterNetworkCallback(it) 
            } catch (e: Exception) { Log.w(TAG, "Error unregistering callback for $id: ${e.message}") }
        }
    }

    override fun getConnectionCount(): Int = peerSockets.size

    /**
     * Successfully established a client connection
     */
    fun onClientConnected(peerId: String, socket: SyncedSocket) {
        // Close previous socket if one exists to prevent zombie readers
        peerSockets[peerId]?.let { 
            try { it.close() } catch (_: Exception) {}
        }
        peerSockets[peerId] = socket
        removePendingConnection(peerId) // Clear retry state on success
    }

    fun getSocketForPeer(peerId: String): SyncedSocket? {
        val canonicalId = resolveCanonicalPeerId(peerId)
        return peerSockets[canonicalId]
    }

    fun canonicalPeerId(peerId: String): String = resolveCanonicalPeerId(peerId)

    fun rebindPeerId(previousPeerId: String, resolvedPeerId: String, socket: SyncedSocket): String {
        if (previousPeerId == resolvedPeerId) {
            peerSockets[resolvedPeerId] = socket
            return resolvedPeerId
        }

        val previousCanonical = resolveCanonicalPeerId(previousPeerId)
        val existing = peerSockets[previousCanonical]
        if (existing === socket) {
            peerSockets.remove(previousCanonical)
        }

        peerSockets[resolvedPeerId]?.let { current ->
            if (current !== socket) {
                try { current.close() } catch (_: Exception) { }
            }
        }
        peerSockets[resolvedPeerId] = socket
        serverSockets.remove(previousCanonical)?.let { serverSockets[resolvedPeerId] = it }
        networkCallbacks.remove(previousCanonical)?.let { networkCallbacks[resolvedPeerId] = it }
        socketAliases[previousPeerId] = resolvedPeerId
        if (previousCanonical != previousPeerId) {
            socketAliases[previousCanonical] = resolvedPeerId
        }
        removePendingConnection(previousPeerId)
        removePendingConnection(resolvedPeerId)

        Log.i(TAG, "Rebound Wi-Fi Aware socket ${previousPeerId.take(8)} -> ${resolvedPeerId.take(8)}")
        return resolvedPeerId
    }

    private fun resolveCanonicalPeerId(peerId: String): String {
        var current = peerId
        val visited = mutableSetOf<String>()
        while (visited.add(current)) {
            val next = socketAliases[current] ?: return current
            current = next
        }
        return current
    }

    fun addServerSocket(peerId: String, socket: ServerSocket) {
        serverSockets[peerId] = socket
    }

    fun addNetworkCallback(peerId: String, callback: ConnectivityManager.NetworkCallback) {
        networkCallbacks[peerId] = callback
    }
    
    /**
     * Clean up all resources
     */
    override fun stop() {
        super.stop()
        val allIds = peerSockets.keys + serverSockets.keys + networkCallbacks.keys
        allIds.toSet().forEach { disconnect(it) }
    }
    
    fun getDebugInfo(): String {
        return buildString {
            appendLine("Aware Connections: ${getConnectionCount()}")
            peerSockets.keys.forEach { pid ->
                appendLine("  - $pid (Socket)")
            }
            if (socketAliases.isNotEmpty()) {
                appendLine("Socket aliases:")
                socketAliases.forEach { (alias, canonical) ->
                    appendLine("  - $alias -> $canonical")
                }
            }
            appendLine("Server Sockets: ${serverSockets.size}")
            serverSockets.keys.forEach { pid ->
                appendLine("  - $pid (Listening)")
            }
            appendLine("Pending Attempts: ${pendingConnections.size}")
            pendingConnections.forEach { (pid, attempt) ->
                appendLine("  - $pid: ${attempt.attempts} attempts")
            }
        }
    }
}
