package com.bitchat.android.mesh

import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.meshgraph.MeshGraphService

/**
 * Shared transport-to-mesh-graph synchronization helper used by phone BLE, Wear OS BLE, and
 * Wi-Fi Aware so direct-peer publication, unidentified-link tracking, and stale-edge pruning
 * follow identical multi-transport rules.
 */
internal object MeshGraphTransportCleanup {

    fun publishDirectState(
        transportId: String,
        localDirectPeers: Set<String>,
        hasUnidentifiedDirectLink: Boolean
    ) {
        try {
            AppStateStore.setTransportDirectPeers(transportId, localDirectPeers)
            AppStateStore.setTransportHasUnidentifiedDirectLink(transportId, hasUnidentifiedDirectLink)
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
        val stillDirectElsewhere = try {
            AppStateStore.hasDirectPeerOnOtherTransport(transportId, peerID)
        } catch (_: Exception) {
            false
        }
        if (!stillDirectElsewhere) {
            try { graphService.removeDirectEdge(myPeerID, peerID) } catch (_: Exception) { }
        }
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
        val stillActiveElsewhere = try {
            AppStateStore.hasPeerOnOtherTransport(transportId, peerID)
        } catch (_: Exception) {
            false
        }
        if (!stillActiveElsewhere) {
            try { graphService.removePeer(peerID) } catch (_: Exception) { }
        }
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
        try { AppStateStore.clearTransportPeers(transportId) } catch (_: Exception) { }
        try { AppStateStore.clearTransportDirectPeers(transportId) } catch (_: Exception) { }

        for (peerID in previousDirectPeers) {
            val stillDirectElsewhere = try {
                AppStateStore.hasDirectPeerOnOtherTransport(transportId, peerID)
            } catch (_: Exception) {
                false
            }
            if (!stillDirectElsewhere) {
                try { graphService.removeDirectEdge(myPeerID, peerID) } catch (_: Exception) { }
            }
        }

        for (peerID in (previousActivePeers + previousDirectPeers)) {
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
}
