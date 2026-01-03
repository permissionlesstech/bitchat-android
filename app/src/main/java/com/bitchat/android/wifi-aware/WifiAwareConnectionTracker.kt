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
    val peerSockets = ConcurrentHashMap<String, Socket>()
    val serverSockets = ConcurrentHashMap<String, ServerSocket>()
    val networkCallbacks = ConcurrentHashMap<String, ConnectivityManager.NetworkCallback>()

    override fun isConnected(id: String): Boolean {
        // We consider it connected if we have a client socket to them
        return peerSockets.containsKey(id)
    }

    override fun disconnect(id: String) {
        Log.d(TAG, "Disconnecting peer $id")
        
        // 1. Close client socket
        peerSockets.remove(id)?.let {
            try { it.close() } catch (e: Exception) { Log.w(TAG, "Error closing socket for $id: ${e.message}") }
        }

        // 2. Close server socket
        serverSockets.remove(id)?.let {
            try { it.close() } catch (e: Exception) { Log.w(TAG, "Error closing server socket for $id: ${e.message}") }
        }

        // 3. Unregister network callback
        networkCallbacks.remove(id)?.let {
            try { cm.unregisterNetworkCallback(it) } catch (e: Exception) { Log.w(TAG, "Error unregistering callback for $id: ${e.message}") }
        }
    }

    override fun getConnectionCount(): Int = peerSockets.size

    /**
     * Successfully established a client connection
     */
    fun onClientConnected(peerId: String, socket: Socket) {
        peerSockets[peerId] = socket
        removePendingConnection(peerId) // Clear retry state on success
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
        peerSockets.keys.toList().forEach { disconnect(it) }
        serverSockets.keys.toList().forEach { disconnect(it) }
        // (disconnect handles map removal)
    }
    
    fun getDebugInfo(): String {
        return buildString {
            appendLine("Aware Connections: ${getConnectionCount()}")
            peerSockets.keys.forEach { pid ->
                appendLine("  - $pid (Socket)")
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
