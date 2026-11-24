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
    private val verifiedSockets = ConcurrentHashMap<String, SyncedSocket>()
    private val socketAliases = ConcurrentHashMap<String, String>()
    private val socketBindingLock = Any()
    val serverSockets = ConcurrentHashMap<String, ServerSocket>()
    val networkCallbacks = ConcurrentHashMap<String, ConnectivityManager.NetworkCallback>()

    /** Invoked whenever active socket connections or verified peer bindings change. */
    @Volatile
    var onConnectionStateChanged: (() -> Unit)? = null

    /** Invoked synchronously when a verified socket is displaced or removed. */
    @Volatile
    var onVerifiedPeerDisconnected: ((String) -> Unit)? = null

    private fun notifyConnectionStateChanged() {
        try { onConnectionStateChanged?.invoke() } catch (_: Exception) { }
    }

    private fun notifyVerifiedPeerDisconnected(peerID: String) {
        try { onVerifiedPeerDisconnected?.invoke(peerID) } catch (_: Exception) { }
    }

    override fun isConnected(id: String): Boolean {
        // We consider it connected if we have a client socket to them
        return getSocketForPeer(id) != null
    }

    override fun disconnect(id: String) {
        synchronized(socketBindingLock) {
            Log.d(TAG, "Disconnecting peer $id")
            val canonicalId = resolveCanonicalPeerId(id)

            // 1. Close client socket
            peerSockets.remove(canonicalId)?.let {
                try { it.close() } catch (e: Exception) { Log.w(TAG, "Error closing socket for $id: ${e.message}") }
            }
            verifiedSockets.remove(canonicalId)
            socketAliases.entries.removeIf { it.key == id || it.key == canonicalId || it.value == canonicalId }

            // 2. Close server socket
            serverSockets.remove(canonicalId)?.let {
                try { it.close() } catch (e: Exception) { Log.w(TAG, "Error closing server socket for $id: ${e.message}") }
            }

            // Ensure any pending/active network request is explicitly released
            releaseNetworkRequest(canonicalId)
            removePendingConnection(id)
            removePendingConnection(canonicalId)
        }
        notifyConnectionStateChanged()
    }

    fun releaseNetworkRequest(id: String) {
        val canonicalId = resolveCanonicalPeerId(id)
        if (!networkCallbacks.containsKey(canonicalId)) return
        
        // 3. Unregister network callback properly from ConnectivityManager
        networkCallbacks.remove(canonicalId)?.let {
            try { 
                Log.d(TAG, "Unregistering network callback for $canonicalId")
                cm.unregisterNetworkCallback(it) 
            } catch (e: Exception) { Log.w(TAG, "Error unregistering callback for $canonicalId: ${e.message}") }
        }
    }

    override fun getConnectionCount(): Int = peerSockets.size

    /**
     * Successfully established a client connection
     */
    fun onClientConnected(peerId: String, socket: SyncedSocket) {
        val displacedVerifiedPeerId = synchronized(socketBindingLock) {
            val canonicalPeerId = resolveCanonicalPeerId(peerId)
            // Close previous socket if one exists to prevent zombie readers
            peerSockets[canonicalPeerId]?.let {
                try { it.close() } catch (_: Exception) {}
            }
            // A newly connected socket is provisional until a validated direct ANNOUNCE binds it.
            val wasVerified = verifiedSockets.remove(canonicalPeerId) != null
            peerSockets[canonicalPeerId] = socket
            removePendingConnection(peerId) // Clear retry state on success
            if (canonicalPeerId != peerId) removePendingConnection(canonicalPeerId)
            if (wasVerified) canonicalPeerId else null
        }
        notifyConnectionStateChanged()
        if (displacedVerifiedPeerId != null) {
            notifyVerifiedPeerDisconnected(displacedVerifiedPeerId)
        }
    }

    fun getSocketForPeer(peerId: String): SyncedSocket? {
        val canonicalId = resolveCanonicalPeerId(peerId)
        return peerSockets[canonicalId]
    }

    fun canonicalPeerId(peerId: String): String = resolveCanonicalPeerId(peerId)

    /**
     * Marks [expectedSocket] as cryptographically verified for its bound peer after a validated
     * direct ANNOUNCE is observed on that socket.
     */
    fun markSocketVerified(peerId: String, expectedSocket: SyncedSocket): Boolean {
        val marked = synchronized(socketBindingLock) {
            val canonical = resolveCanonicalPeerId(peerId)
            if (peerSockets[canonical] !== expectedSocket) return@synchronized false
            verifiedSockets[canonical] = expectedSocket
            true
        }
        if (marked) notifyConnectionStateChanged()
        return marked
    }

    /**
     * Returns true iff [expectedSocket] is still the active socket for [peerId] AND has been
     * verified by a validated direct ANNOUNCE.
     */
    fun isSocketVerified(peerId: String, expectedSocket: SyncedSocket): Boolean =
        synchronized(socketBindingLock) {
            val canonical = resolveCanonicalPeerId(peerId)
            peerSockets[canonical] === expectedSocket && verifiedSockets[canonical] === expectedSocket
        }

    /**
     * True when at least one active writable socket has not yet been verified by a direct ANNOUNCE.
     */
    fun hasUnidentifiedConnection(): Boolean = synchronized(socketBindingLock) {
        peerSockets.any { (pid, sock) -> verifiedSockets[pid] !== sock }
    }

    /**
     * Canonical peer IDs whose active sockets have been verified by a direct ANNOUNCE.
     */
    fun getVerifiedConnectedPeerIDs(): Set<String> = synchronized(socketBindingLock) {
        peerSockets.entries
            .filter { (pid, sock) -> verifiedSockets[pid] === sock && pid.isNotBlank() }
            .map { it.key }
            .toSet()
    }

    /**
     * Resolves the canonical peerID for [peerId] only if [expectedSocket] is still the active
     * socket bound to that peer, preventing a stale reader from attributing late packets to a
     * replacement connection.
     */
    fun canonicalPeerIdIfSocketCurrent(peerId: String, expectedSocket: SyncedSocket): String? =
        synchronized(socketBindingLock) {
            val canonical = resolveCanonicalPeerId(peerId)
            if (peerSockets[canonical] === expectedSocket) canonical else null
        }

    /**
     * Resolves the canonical peerID for [peerId] only if [expectedSocket] is both current and
     * verified by a direct ANNOUNCE. Provisional discovery sockets return null until verified.
     */
    fun verifiedCanonicalPeerIdIfSocketCurrent(peerId: String, expectedSocket: SyncedSocket): String? =
        synchronized(socketBindingLock) {
            val canonical = resolveCanonicalPeerId(peerId)
            if (peerSockets[canonical] === expectedSocket && verifiedSockets[canonical] === expectedSocket) {
                canonical
            } else {
                null
            }
        }

    fun rebindPeerId(previousPeerId: String, resolvedPeerId: String, socket: SyncedSocket): String {
        val result = synchronized(socketBindingLock) {
            rebindPeerIdLocked(previousPeerId, resolvedPeerId, socket)
        }
        notifyConnectionStateChanged()
        return result
    }

    /**
     * Atomically require that [expectedSocket] is still the active provisional transport before
     * rebinding it. This closes the gap where a replacement socket could land after ANNOUNCE
     * validation but before mutation and the stale socket would become canonical.
     */
    fun rebindPeerIdIfCurrent(
        previousPeerId: String,
        resolvedPeerId: String,
        expectedSocket: SyncedSocket
    ): Boolean {
        val rebound = synchronized(socketBindingLock) {
            val previousCanonical = resolveCanonicalPeerId(previousPeerId)
            if (peerSockets[previousCanonical] !== expectedSocket) return@synchronized false
            val resolvedCanonical = resolveCanonicalPeerId(resolvedPeerId)
            val existingResolvedSocket = peerSockets[resolvedCanonical]
            if (existingResolvedSocket != null && existingResolvedSocket !== expectedSocket) {
                return@synchronized false
            }
            rebindPeerIdLocked(previousPeerId, resolvedPeerId, expectedSocket)
            verifiedSockets[resolvedPeerId] = expectedSocket
            true
        }
        if (rebound) notifyConnectionStateChanged()
        return rebound
    }

    private fun rebindPeerIdLocked(
        previousPeerId: String,
        resolvedPeerId: String,
        socket: SyncedSocket
    ): String {
        if (previousPeerId == resolvedPeerId) {
            peerSockets[resolvedPeerId] = socket
            return resolvedPeerId
        }

        val previousCanonical = resolveCanonicalPeerId(previousPeerId)
        val existing = peerSockets[previousCanonical]
        if (existing === socket) {
            peerSockets.remove(previousCanonical)
        }
        verifiedSockets.remove(previousCanonical)

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
        val canonicalId = resolveCanonicalPeerId(peerId)
        serverSockets.put(canonicalId, socket)?.let {
            try { it.close() } catch (e: Exception) { Log.w(TAG, "Error closing replaced server socket for $peerId: ${e.message}") }
        }
    }

    fun hasOpenServerSocket(peerId: String): Boolean {
        val canonicalId = resolveCanonicalPeerId(peerId)
        val socket = serverSockets[canonicalId] ?: return false
        if (!socket.isClosed) return true
        serverSockets.remove(canonicalId)
        return false
    }

    fun closeServerSocket(peerId: String) {
        val canonicalId = resolveCanonicalPeerId(peerId)
        serverSockets.remove(canonicalId)?.let {
            try { it.close() } catch (e: Exception) { Log.w(TAG, "Error closing server socket for $peerId: ${e.message}") }
        }
    }

    fun hasPendingDataPathRequest(exceptPeerId: String? = null): Boolean {
        val exceptCanonical = exceptPeerId?.let { resolveCanonicalPeerId(it) }
        return pendingDataPathPeerIds(exceptCanonical).isNotEmpty()
    }

    fun pendingDataPathPeerIds(exceptPeerId: String? = null): Set<String> {
        val exceptCanonical = exceptPeerId?.let { resolveCanonicalPeerId(it) }
        val pendingIds = linkedSetOf<String>()

        pendingConnections.keys.forEach { peerId ->
            val canonicalId = resolveCanonicalPeerId(peerId)
            if (canonicalId != exceptCanonical && !isConnected(canonicalId)) {
                pendingIds.add(canonicalId)
            }
        }

        networkCallbacks.keys.forEach { peerId ->
            val canonicalId = resolveCanonicalPeerId(peerId)
            if (canonicalId != exceptCanonical && !isConnected(canonicalId)) {
                pendingIds.add(canonicalId)
            }
        }

        return pendingIds
    }

    fun pendingServerDataPathPeerIds(exceptPeerId: String? = null): Set<String> {
        val exceptCanonical = exceptPeerId?.let { resolveCanonicalPeerId(it) }
        return serverSockets.keys.map { resolveCanonicalPeerId(it) }
            .filter { peerId ->
                peerId != exceptCanonical &&
                    !isConnected(peerId) &&
                    networkCallbacks.containsKey(peerId) &&
                    serverSockets[peerId]?.isClosed == false
            }
            .toSet()
    }

    fun cancelPendingServerDataPaths(exceptPeerId: String? = null): Set<String> {
        val cancelled = pendingServerDataPathPeerIds(exceptPeerId)
        cancelled.forEach { disconnect(it) }
        return cancelled
    }

    fun addNetworkCallback(peerId: String, callback: ConnectivityManager.NetworkCallback) {
        val canonicalId = resolveCanonicalPeerId(peerId)
        networkCallbacks.put(canonicalId, callback)?.let {
            try {
                Log.d(TAG, "Replacing network callback for $canonicalId")
                cm.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering replaced callback for $canonicalId: ${e.message}")
            }
        }
    }
    
    /**
     * Clean up all resources
     */
    override fun stop() {
        super.stop()
        val allIds = peerSockets.keys + serverSockets.keys + networkCallbacks.keys
        allIds.toSet().forEach { disconnect(it) }
        verifiedSockets.clear()
        notifyConnectionStateChanged()
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
