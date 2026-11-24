package com.bitchat.android.wifiaware

import android.util.Log
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.services.meshgraph.BroadcastPruningPolicy
import com.bitchat.android.services.meshgraph.MeshGraphService
import java.io.IOException

/**
 * Handles broadcast packet transmission over connected Wi-Fi Aware sockets while skipping
 * echo-back to the incoming previous hop / origin and pruning fresh confirmed neighbors of
 * either origin or previous hop.
 */
internal class WifiAwarePacketBroadcaster(
    private val myPeerID: String,
    private val connectionTracker: WifiAwareConnectionTracker,
    private val meshGraphProvider: () -> MeshGraphService = MeshGraphService::getInstance,
    private val edgeMaxAgeMs: Long = MeshGraphService.DEFAULT_EDGE_MAX_AGE_MS
) {
    companion object {
        private const val TAG = "WifiAwareBroadcaster"
    }

    fun broadcastSinglePacket(routed: RoutedPacket): Boolean {
        val data = routed.packet.toBinaryData() ?: return false
        val relayCanonicalPeerID = routed.relayAddress
            ?.takeIf { it.isNotBlank() }
            ?.let { connectionTracker.canonicalPeerId(it) }
        val resolvedPreviousHopPeerID = routed.previousHopPeerID?.takeIf { it.isNotBlank() }
            ?: relayCanonicalPeerID
        val graphService = meshGraphProvider()

        var accepted = false
        connectionTracker.peerSockets.forEach { (pid, sock) ->
            val canonicalTargetPeerID = connectionTracker.canonicalPeerId(pid)

            // Also guard against alias drift when a provisional relayAddress was rebound to its
            // canonical peerID between packet arrival and relay transmission.
            if (relayCanonicalPeerID != null && canonicalTargetPeerID == relayCanonicalPeerID) {
                return@forEach
            }

            if (BroadcastPruningPolicy.shouldSkipTarget(
                    routed = routed,
                    myPeerID = myPeerID,
                    targetPeerID = canonicalTargetPeerID,
                    targetAddress = pid,
                    resolvedPreviousHopPeerID = resolvedPreviousHopPeerID,
                    graphService = graphService,
                    edgeMaxAgeMs = edgeMaxAgeMs
                )
            ) {
                return@forEach
            }

            try {
                sock.write(data)
                accepted = true
            } catch (e: IOException) {
                Log.e(TAG, "TX: write failed to ${canonicalTargetPeerID.take(8)}: ${e.message}")
            }
        }
        return accepted
    }
}
