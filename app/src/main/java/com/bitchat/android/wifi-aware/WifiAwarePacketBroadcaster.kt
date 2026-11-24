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
    private val edgeMaxAgeMs: Long = MeshGraphService.DEFAULT_EDGE_MAX_AGE_MS,
    private val ingressSocketResolver: ((relayAddress: String, ingressLinkID: String) -> SyncedSocket?)? = null
) {
    companion object {
        private const val TAG = "WifiAwareBroadcaster"
    }

    fun broadcastSinglePacket(routed: RoutedPacket): Boolean {
        val data = routed.packet.toBinaryData() ?: return false
        val ingressLinkID = routed.ingressLinkID
        val hasLinkGeneration = ingressLinkID != null
        val ingressSocket = if (ingressLinkID != null && !routed.relayAddress.isNullOrBlank()) {
            ingressSocketResolver?.invoke(routed.relayAddress, ingressLinkID)
        } else {
            null
        }
        val legacyRelayCanonicalPeerID = if (!hasLinkGeneration) {
            routed.relayAddress
                ?.takeIf { it.isNotBlank() }
                ?.let { connectionTracker.canonicalPeerId(it) }
        } else {
            null
        }
        val resolvedPreviousHopPeerID = routed.previousHopPeerID?.takeIf { it.isNotBlank() }
            ?: legacyRelayCanonicalPeerID
        val graphService = meshGraphProvider()

        var accepted = false
        connectionTracker.peerSockets.forEach { (pid, sock) ->
            // When ingress link generation is tracked, skip only the exact incoming socket instance
            // so a late packet from a replaced socket cannot suppress a replacement peer/socket.
            if (hasLinkGeneration && ingressSocket != null && sock === ingressSocket) {
                return@forEach
            }

            val canonicalTargetPeerID = connectionTracker.canonicalPeerId(pid)
            val isTargetVerified = connectionTracker.isSocketVerified(pid, sock)

            // Legacy (no ingressLinkID) alias drift guard for rebound provisional relayAddress
            if (!hasLinkGeneration &&
                legacyRelayCanonicalPeerID != null &&
                canonicalTargetPeerID == legacyRelayCanonicalPeerID
            ) {
                return@forEach
            }

            if (BroadcastPruningPolicy.shouldSkipTarget(
                    routed = routed,
                    myPeerID = myPeerID,
                    targetPeerID = if (isTargetVerified) canonicalTargetPeerID else null,
                    targetAddress = if (hasLinkGeneration) null else pid,
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
