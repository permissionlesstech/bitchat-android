package com.bitchat.android.settings

import com.bitchat.android.ui.debug.ConnectedDevice
import com.bitchat.android.ui.debug.ConnectionType
import kotlinx.coroutines.flow.StateFlow

/**
 * What Debug settings reads from, and does to, the app's transports: the
 * Wi-Fi Aware controller and the Bluetooth mesh service, both of which live in
 * the app.
 */
interface TransportDiagnostics {

    // Wi-Fi Aware

    val wifiAwareStatus: StateFlow<WifiAwareStatus>

    /** Peer ID to IP address, for each open Wi-Fi Aware socket. */
    val wifiAwareConnectedPeers: StateFlow<Map<String, String>>

    /** Peer ID to nickname, or "" when none is known, for each peer Wi-Fi Aware has discovered. */
    val wifiAwareDiscoveredPeers: StateFlow<Map<String, String>>

    /** Sends a Wi-Fi Aware announce, if the Wi-Fi Aware service is running. */
    fun sendWifiAwareAnnounce()

    /** The runtime permissions Wi-Fi Aware needs on this device. */
    fun wifiAwarePermissions(): List<String>

    // Bluetooth mesh

    /**
     * A handle to the mesh service running now, or null when none is. It never
     * creates or replaces one. The handle stays the same object until the mesh
     * changes; Debug calls this on every poll, off the main thread.
     */
    fun openMesh(): MeshDiagnostics?
}

/**
 * Wi-Fi Aware as Debug shows it. [unsupportedReason] is why it is unsupported;
 * null until that is evaluated, or when no reason is given.
 */
data class WifiAwareStatus(
    val supported: Boolean,
    val available: Boolean,
    val running: Boolean,
    val unsupportedReason: String?,
)

/** One mesh service instance, as Debug settings sees it. */
interface MeshDiagnostics {

    val myPeerID: String

    /** The connected devices and the peer lookups, read together once. */
    fun snapshot(): MeshSnapshot

    fun setGattServerRunning(on: Boolean)
    fun setGattClientRunning(on: Boolean)

    fun localAdapterAddress(): String?
    fun connectToDevice(address: String)
    fun disconnectDevice(address: String)
}

/**
 * The mesh at one moment: each connected device, Bluetooth address to peer ID,
 * peer ID to nickname, and the peers connected directly.
 */
data class MeshSnapshot(
    val devices: List<ConnectedDevice>,
    val addressToPeer: Map<String, String>,
    val nicknames: Map<String, String>,
    val directPeers: Set<String>,
) {
    companion object {
        /**
         * Builds a snapshot from the raw reads: each connected device as
         * (address, is the GATT client, RSSI), labelled with its peer, that
         * peer's nickname and whether it is direct.
         */
        fun assemble(
            entries: List<Triple<String, Boolean, Int?>>,
            addressToPeer: Map<String, String>,
            nicknames: Map<String, String>,
            directPeers: Set<String>,
        ): MeshSnapshot {
            val devices = entries.map { (address, isClient, rssi) ->
                val pid = addressToPeer[address]
                ConnectedDevice(
                    deviceAddress = address,
                    peerID = pid,
                    nickname = pid?.let { nicknames[it] },
                    rssi = rssi,
                    connectionType = if (isClient) ConnectionType.GATT_CLIENT else ConnectionType.GATT_SERVER,
                    isDirectConnection = pid?.let { it in directPeers } ?: false,
                )
            }
            return MeshSnapshot(devices, addressToPeer, nicknames, directPeers)
        }
    }
}
