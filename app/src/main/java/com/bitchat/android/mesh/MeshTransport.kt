package com.bitchat.android.mesh

import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket

/**
 * Transport abstraction used by MeshCore to send packets via a specific medium.
 */
interface MeshTransport {
    val id: String

    /**
     * Broadcasts a packet and reports whether at least one concrete transport write was accepted.
     */
    fun broadcastPacket(routed: RoutedPacket): Boolean

    fun sendPacketToPeer(peerID: String, packet: BitchatPacket): Boolean

    /**
     * Send through an exact transport generation rather than a reusable peer alias.
     * Transports that cannot prove the link identity must decline the operation.
     */
    fun sendPacketToLink(
        relayAddress: String,
        ingressLinkID: String,
        packet: BitchatPacket
    ): Boolean = false

    fun cancelTransfer(transferId: String): Boolean = false

    fun getDeviceAddressForPeer(peerID: String): String? = null

    fun getDeviceAddressToPeerMapping(): Map<String, String> = emptyMap()

    fun getTransportDebugInfo(): String = ""
}
