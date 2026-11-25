package com.bitchat.android.mesh

import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.meshgraph.MeshGraphService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shared transport-to-mesh-graph synchronization helper used by phone BLE, Wear OS BLE, and
 * Wi-Fi Aware so direct-peer publication, unidentified-link tracking, stale-edge pruning, and
 * prompt topology-change announcements follow identical multi-transport rules.
 */
internal object MeshGraphTransportCleanup {

    const val TOPOLOGY_CHANGE_ANNOUNCE_DELAY_MS: Long = 500L

    fun publishDirectState(
        transportId: String,
        localDirectPeers: Set<String>,
        hasUnidentifiedDirectLink: Boolean
    ) {
        try {
            AppStateStore.setTransportDirectState(transportId, localDirectPeers, hasUnidentifiedDirectLink)
        } catch (_: Exception) { }
    }

    /**
     * Updates published direct-link state after a link disconnects and prunes `(myPeerID, peerID)`
     * from [MeshGraphService] only when [peerID] has no remaining direct link on this transport or
     * on any other active transport.
     */
    fun onDirectLinkLost(
        myPeerID: String,
        transportId: String,
        peerID: String,
        remainingLocalDirectPeers: Set<String>,
        hasUnidentifiedDirectLink: Boolean,
        graphService: MeshGraphService = MeshGraphService.getInstance()
    ) {
        publishDirectState(transportId, remainingLocalDirectPeers, hasUnidentifiedDirectLink)
        if (peerID in remainingLocalDirectPeers) return
        removeDirectEdgeIfAbsentElsewhere(myPeerID, transportId, peerID, graphService)
    }

    /**
     * Updates published direct-link state after a peer is removed from this transport and prunes
     * its direct edge and (when absent from all other transports) graph node.
     */
    fun onPeerRemoved(
        myPeerID: String,
        transportId: String,
        peerID: String,
        remainingLocalDirectPeers: Set<String>,
        hasUnidentifiedDirectLink: Boolean,
        graphService: MeshGraphService = MeshGraphService.getInstance()
    ) {
        onDirectLinkLost(
            myPeerID = myPeerID,
            transportId = transportId,
            peerID = peerID,
            remainingLocalDirectPeers = remainingLocalDirectPeers,
            hasUnidentifiedDirectLink = hasUnidentifiedDirectLink,
            graphService = graphService
        )
        removePeerIfAbsentElsewhere(transportId, peerID, graphService)
    }

    /**
     * Cleans up [AppStateStore] and [MeshGraphService] when an entire transport is disabled or
     * torn down, before transport-internal connection maps are cleared.
     */
    fun onTransportDisabled(
        myPeerID: String,
        transportId: String,
        previousDirectPeers: Set<String>,
        previousActivePeers: Set<String>,
        graphService: MeshGraphService = MeshGraphService.getInstance()
    ) {
        try { AppStateStore.clearTransportState(transportId) } catch (_: Exception) { }

        for (peerID in previousDirectPeers) {
            removeDirectEdgeIfAbsentElsewhere(myPeerID, transportId, peerID, graphService)
        }

        for (peerID in (previousActivePeers + previousDirectPeers)) {
            removePeerIfAbsentElsewhere(transportId, peerID, graphService)
        }
    }

    /**
     * Schedules a prompt (~500 ms) topology-change ANNOUNCE when [removedPeerID] lost its last
     * direct link on this transport while at least one other direct peer remains reachable on
     * this transport OR another active transport. Checks [isServiceActive] both before scheduling
     * and right before sending so deactivation cancels any pending announcement.
     */
    fun scheduleTopologyChangeAnnounce(
        scope: CoroutineScope,
        removedPeerID: String,
        remainingLocalDirectPeers: Set<String>,
        isServiceActive: () -> Boolean,
        sendAnnounce: () -> Unit,
        delayMs: Long = TOPOLOGY_CHANGE_ANNOUNCE_DELAY_MS
    ): Job? {
        if (removedPeerID in remainingLocalDirectPeers) return null
        val anyDirectPeersRemaining = remainingLocalDirectPeers.isNotEmpty() || try {
            AppStateStore.getDirectPeers().isNotEmpty()
        } catch (_: Exception) {
            false
        }
        if (!anyDirectPeersRemaining || !isServiceActive()) return null
        return scope.launch {
            delay(delayMs)
            if (isServiceActive()) {
                sendAnnounce()
            }
        }
    }

    private fun removeDirectEdgeIfAbsentElsewhere(
        myPeerID: String,
        transportId: String,
        peerID: String,
        graphService: MeshGraphService
    ) {
        val stillDirectElsewhere = try {
            AppStateStore.hasDirectPeerOnOtherTransport(transportId, peerID)
        } catch (_: Exception) {
            false
        }
        if (!stillDirectElsewhere) {
            try { graphService.removeDirectEdge(myPeerID, peerID) } catch (_: Exception) { }
        }
    }

    private fun removePeerIfAbsentElsewhere(
        transportId: String,
        peerID: String,
        graphService: MeshGraphService
    ) {
        val stillActiveElsewhere = try {
            AppStateStore.hasPeerOnOtherTransport(transportId, peerID)
        } catch (_: Exception) {
            false
        }
        if (!stillActiveElsewhere) {
            try { graphService.removePeer(peerID) } catch (_: Exception) { }
        }
    }
}
