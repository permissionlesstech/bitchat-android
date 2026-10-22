package com.bitchat.android.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * What Debug settings reads from, and does to, the app's transports: the
 * Wi-Fi Aware controller and the Bluetooth mesh service, both of which live in
 * the app. One member per thing the screen uses, named for what it does there.
 */
interface TransportDiagnostics {

    // Wi-Fi Aware

    val wifiAwareSupported: StateFlow<Boolean>
    val wifiAwareAvailable: StateFlow<Boolean>

    /** Why Wi-Fi Aware is unsupported; null until it is evaluated, or when no reason is given. */
    val wifiAwareSupportReason: Flow<String?>

    /** The value [wifiAwareSupportReason] holds right now, to collect it from without a blank frame. */
    fun currentWifiAwareSupportReason(): String?
    val wifiAwareRunning: StateFlow<Boolean>

    /** Peer ID to IP address, for each open Wi-Fi Aware socket. */
    val wifiAwareConnectedPeers: StateFlow<Map<String, String>>

    /** Peer ID to nickname, for each peer Wi-Fi Aware has learned a nickname for. */
    val wifiAwareKnownPeers: StateFlow<Map<String, String>>
    val wifiAwareDiscoveredPeers: StateFlow<Set<String>>

    /** Sends a Wi-Fi Aware announce, if the Wi-Fi Aware service is running. */
    fun sendWifiAwareAnnounce()

    /** The runtime permissions Wi-Fi Aware needs on this device. */
    fun wifiAwarePermissions(): List<String>

    // Bluetooth mesh

    /**
     * Gets the app's mesh service, creating or replacing it as needed, and
     * returns a handle bound to that instance. Debug calls this once per visit.
     */
    fun openMesh(): MeshDiagnostics
}

/** One mesh service instance, as Debug settings sees it. */
interface MeshDiagnostics {

    val myPeerID: String

    /** Peer ID to nickname. */
    fun peerNicknames(): Map<String, String>

    /** Bluetooth device address to peer ID. */
    fun deviceAddressToPeerMapping(): Map<String, String>

    fun isDirectConnection(peerID: String): Boolean

    /** Each connected device as (address, is the GATT client, RSSI). */
    fun connectedDeviceEntries(): List<Triple<String, Boolean, Int?>>

    fun startGattServer()
    fun stopGattServer()
    fun startGattClient()
    fun stopGattClient()

    fun localAdapterAddress(): String?
    fun connectToDevice(address: String)
    fun disconnectDevice(address: String)
}
