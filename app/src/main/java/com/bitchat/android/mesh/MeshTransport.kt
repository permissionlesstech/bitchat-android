package com.bitchat.android.mesh

import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket

/**
 * Transport abstraction used by MeshCore to send packets via a specific medium.
 */
interface MeshTransport {
    val id: String

    fun broadcastPacket(routed: RoutedPacket)

    fun sendPacketToPeer(peerID: String, packet: BitchatPacket)

    fun cancelTransfer(transferId: String): Boolean = false

    fun getDeviceAddressForPeer(peerID: String): String? = null

    fun getDeviceAddressToPeerMapping(): Map<String, String> = emptyMap()

    fun getTransportDebugInfo(): String = ""
}
