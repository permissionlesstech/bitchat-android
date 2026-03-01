package com.bitchat.android.sync

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages outgoing sync requests and validates incoming responses.
 * 
 * Allows attributing RSR (Request-Sync Response) packets to specific peers
 * that we have actively requested sync from.
 */
class RequestSyncManager {

    companion object {
        private const val TAG = "RequestSyncManager"
        private const val RESPONSE_WINDOW_MS = 30_000L // Allow responses for 30s after request
    }

    // Tracks the timestamp of the last sync request sent to a peer
    private val pendingRequests = ConcurrentHashMap<String, Long>()

    /**
     * Register that we are sending a sync request to a peer.
     * @param peerID The peer we are requesting sync from
     */
    fun registerRequest(peerID: String) {
        Log.d(TAG, "Registering sync request to $peerID")
        pendingRequests[peerID] = System.currentTimeMillis()
    }

    /**
     * Check if a packet from a peer is a valid response to a sync request.
     * 
     * @param peerID The sender of the packet
     * @param isRSR Whether the packet is marked as a Request-Sync Response
     * @return true if we have a pending request for this peer and the window is open
     */
    fun isValidResponse(peerID: String, isRSR: Boolean): Boolean {
        if (!isRSR) return false

        val requestTime = pendingRequests[peerID]
        if (requestTime == null) {
            Log.w(TAG, "Received unsolicited RSR packet from $peerID")
            return false
        }

        val now = System.currentTimeMillis()
        if (now - requestTime > RESPONSE_WINDOW_MS) {
            Log.w(TAG, "Received RSR packet from $peerID outside of response window")
            pendingRequests.remove(peerID) // Cleanup expired
            return false
        }

        return true
    }

    /**
     * Periodic cleanup of expired requests
     */
    fun cleanup() {
        val now = System.currentTimeMillis()
        val expired = pendingRequests.filterValues { now - it > RESPONSE_WINDOW_MS }.keys
        if (expired.isNotEmpty()) {
            Log.d(TAG, "Cleaning up ${expired.size} expired sync requests")
            expired.forEach { pendingRequests.remove(it) }
        }
    }
}
