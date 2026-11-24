package com.bitchat.android.model

import com.bitchat.android.protocol.BitchatPacket

/**
 * Process-local source ingress identity carried across [com.bitchat.android.service.TransportBridgeService]
 * so a target transport's egress worker can re-verify that the source transport's ingress link
 * generation is still current before applying neighbor pruning. Never serialized onto the wire.
 */
data class BridgedIngressOrigin(
    val sourceTransportId: String,
    val relayAddress: String,
    val ingressLinkID: String
)

/**
 * Represents a routed packet with additional metadata
 * Used for processing and routing packets in the mesh network
 */
data class RoutedPacket(
    val packet: BitchatPacket,
    val peerID: String? = null,           // Who sent it (parsed from packet.senderID)
    val relayAddress: String? = null,     // Address it came from (for avoiding loopback)
    val transferId: String? = null,       // Optional stable transfer ID for progress tracking
    /** Exact fragments admitted during private-media prepare; never rebuild them at commit. */
    val preparedPackets: List<BitchatPacket>? = null,
    // Opaque, process-local ingress identity. Unlike relayAddress, this distinguishes replacement
    // sockets for the same provisional peer and must never be serialized onto the mesh.
    val ingressLinkID: String? = null,
    // Immediate 1-hop neighbor peerID that handed us this packet (distinct from origin peerID).
    // Process-local only; never serialized onto the wire.
    val previousHopPeerID: String? = null,
    // Process-local relay hint disabling fresh-confirmed neighbor pruning for conservative
    // topology-healing or failed-source-route relays while still preventing echo-back.
    val skipNeighborPruning: Boolean = false,
    // Process-local source transport ingress generation preserved across cross-transport bridging.
    val bridgedIngressOrigin: BridgedIngressOrigin? = null
)
