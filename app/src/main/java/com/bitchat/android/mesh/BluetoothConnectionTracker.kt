package com.bitchat.android.mesh

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tracks all Bluetooth connections and handles cleanup
 */
class BluetoothConnectionTracker(
    private val connectionScope: CoroutineScope,
    private val powerManager: PowerManager
) : MeshConnectionTracker(connectionScope, TAG) {
    
    companion object {
        private const val TAG = "BluetoothConnectionTracker"
        private const val CLEANUP_DELAY = com.bitchat.android.util.AppConstants.Mesh.CONNECTION_CLEANUP_DELAY_MS
    }
    
    // Connection tracking - reduced memory footprint
    private val connectedDevices = ConcurrentHashMap<String, DeviceConnection>()
    private val subscribedDevices = CopyOnWriteArrayList<BluetoothDevice>()
    val addressPeerMap = ConcurrentHashMap<String, String>()
    // Track whether we have seen the first ANNOUNCE on a given device connection
    private val firstAnnounceSeen = ConcurrentHashMap<String, Boolean>()
    // RSSI tracking from scan results (for devices we discover but may connect as servers)
    private val scanRSSI = ConcurrentHashMap<String, Int>()
    
    /**
     * Consolidated device connection information
     */
    data class DeviceConnection(
        val device: BluetoothDevice,
        val gatt: BluetoothGatt? = null,
        val characteristic: BluetoothGattCharacteristic? = null,
        val rssi: Int = Int.MIN_VALUE,
        val isClient: Boolean = false,
        val connectedAt: Long = System.currentTimeMillis(),
        val peerID: String? = null
    )
    
    override fun start() {
        super.start()
    }
    
    override fun stop() {
        super.stop()
        cleanupAllConnections()
        clearAllConnections()
    }

    // Abstract implementations
    override fun isConnected(id: String): Boolean = connectedDevices.containsKey(id)
    
    override fun disconnect(id: String) {
        connectedDevices[id]?.gatt?.let {
            try { it.disconnect() } catch (_: Exception) { }
        }
        cleanupDeviceConnection(id)
        Log.d(TAG, "Requested disconnect for $id")
    }

    override fun getConnectionCount(): Int = connectedDevices.size
    
    /**
     * Add a device connection
     */
    fun addDeviceConnection(deviceAddress: String, deviceConn: DeviceConnection) {
        Log.d(TAG, "Tracker: Adding device connection for $deviceAddress (isClient: ${deviceConn.isClient}")
        connectedDevices[deviceAddress] = deviceConn
        removePendingConnection(deviceAddress)
        // Mark as awaiting first ANNOUNCE on this connection
        firstAnnounceSeen[deviceAddress] = false
    }
    
    /**
     * Update a device connection
     */
    fun updateDeviceConnection(deviceAddress: String, deviceConn: DeviceConnection) {
        connectedDevices[deviceAddress] = deviceConn
    }
    
    /**
     * Get a device connection
     */
    fun getDeviceConnection(deviceAddress: String): DeviceConnection? {
        return connectedDevices[deviceAddress]
    }
    
    /**
     * Get all connected devices
     */
    fun getConnectedDevices(): Map<String, DeviceConnection> {
        return connectedDevices.toMap()
    }
    
    /**
     * Get subscribed devices (for server connections)
     */
    fun getSubscribedDevices(): List<BluetoothDevice> {
        return subscribedDevices.toList()
    }
    
    /**
     * Get current RSSI for a device address
     */
    fun getDeviceRSSI(deviceAddress: String): Int? {
        return connectedDevices[deviceAddress]?.rssi?.takeIf { it != Int.MIN_VALUE }
    }
    
    /**
     * Store RSSI from scan results
     */
    fun updateScanRSSI(deviceAddress: String, rssi: Int) {
        scanRSSI[deviceAddress] = rssi
    }
    
    /**
     * Get best available RSSI for a device (connection RSSI preferred, then scan RSSI)
     */
    fun getBestRSSI(deviceAddress: String): Int? {
        // Prefer connection RSSI if available and valid
        connectedDevices[deviceAddress]?.rssi?.takeIf { it != Int.MIN_VALUE }?.let { return it }
        
        // Fall back to scan RSSI
        return scanRSSI[deviceAddress]
    }
    
    /**
     * Add a subscribed device
     */
    fun addSubscribedDevice(device: BluetoothDevice) {
        subscribedDevices.add(device)
    }
    
    /**
     * Remove a subscribed device
     */
    fun removeSubscribedDevice(device: BluetoothDevice) {
        subscribedDevices.remove(device)
    }
    
    /**
     * Check if device is already connected
     */
    fun isDeviceConnected(deviceAddress: String): Boolean = isConnected(deviceAddress)

    /**
     * Check if a peer is already connected (by PeerID)
     */
    fun isPeerConnected(peerID: String): Boolean {
        // Only consider actual connected devices that have identified themselves
        return connectedDevices.values.any { it.peerID == peerID }
    }
    
    /**
     * Disconnect a specific device (by MAC address)
     */
    fun disconnectDevice(deviceAddress: String) = disconnect(deviceAddress)
    
    /**
     * Get connected device count
     */
    fun getConnectedDeviceCount(): Int = getConnectionCount()
    
    /**
     * Check if connection limit is reached
     */
    /**
     * Check if a new client connection is allowed based on limits
     */
    fun canConnectAsClient(maxOverall: Int, maxClient: Int): Boolean {
        val total = connectedDevices.size
        val clients = connectedDevices.values.count { it.isClient }
        return total < maxOverall && clients < maxClient
    }
    
    /**
     * Calculate which connections should be evicted to satisfy limits.
     * Logic:
     * 1. Enforce strict role limits (maxClient, maxServer) - evict oldest excess.
     * 2. Enforce overall limit (maxOverall) - evict oldest remaining, preferring clients.
     */
    fun getConnectionsToEvict(maxOverall: Int, maxServer: Int, maxClient: Int): List<DeviceConnection> {
        val toEvict = mutableSetOf<DeviceConnection>()
        val currentDevices = connectedDevices.values.toList()
        
        // 1. Enforce Role Limits
        val clients = currentDevices.filter { it.isClient }.sortedBy { it.connectedAt }
        if (clients.size > maxClient) {
            toEvict.addAll(clients.take(clients.size - maxClient))
        }
        
        val servers = currentDevices.filter { !it.isClient }.sortedBy { it.connectedAt }
        if (servers.size > maxServer) {
            toEvict.addAll(servers.take(servers.size - maxServer))
        }
        
        // 2. Enforce Overall Limit
        // Count how many would remain after the above evictions
        val remaining = currentDevices.filter { !toEvict.contains(it) }
        if (remaining.size > maxOverall) {
            val excessCount = remaining.size - maxOverall
            
            // Explicitly prefer evicting clients first
            val clientCandidates = remaining.filter { it.isClient }.sortedBy { it.connectedAt }
            val serverCandidates = remaining.filter { !it.isClient }.sortedBy { it.connectedAt }
            
            var needed = excessCount
            
            // Take from clients first
            val fromClients = clientCandidates.take(needed)
            toEvict.addAll(fromClients)
            needed -= fromClients.size
            
            // If still need more, take from servers
            if (needed > 0) {
                val fromServers = serverCandidates.take(needed)
                toEvict.addAll(fromServers)
            }
        }
        
        return toEvict.toList()
    }
    
    /**
     * Clean up a specific device connection
     */
    fun cleanupDeviceConnection(deviceAddress: String) {
        connectedDevices.remove(deviceAddress)?.let { deviceConn ->
            subscribedDevices.removeAll { it.address == deviceAddress }
            addressPeerMap.remove(deviceAddress)
        }
        firstAnnounceSeen.remove(deviceAddress)
        Log.d(TAG, "Cleaned up device connection for $deviceAddress")
    }
    
    /**
     * Clean up all connections
     */
    private fun cleanupAllConnections() {
        connectedDevices.values.forEach { deviceConn ->
            deviceConn.gatt?.disconnect()
        }
        
        connectionScope.launch {
            delay(CLEANUP_DELAY)
            
            connectedDevices.values.forEach { deviceConn ->
                try {
                    deviceConn.gatt?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing GATT during cleanup: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Clear all connection tracking
     */
    private fun clearAllConnections() {
        connectedDevices.clear()
        subscribedDevices.clear()
        addressPeerMap.clear()
        pendingConnections.clear()
        scanRSSI.clear()
        firstAnnounceSeen.clear()
    }

    /**
     * Mark that we have received the first ANNOUNCE over this device connection.
     */
    fun noteAnnounceReceived(deviceAddress: String) {
        firstAnnounceSeen[deviceAddress] = true
    }

    /**
     * Check whether the first ANNOUNCE has been seen for a device connection.
     */
    fun hasSeenFirstAnnounce(deviceAddress: String): Boolean {
        return firstAnnounceSeen[deviceAddress] == true
    }
    
    /**
     * Get debug information
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("Connected Devices: ${connectedDevices.size} / ${powerManager.getMaxConnections()}")
            connectedDevices.forEach { (address, deviceConn) ->
                val age = (System.currentTimeMillis() - deviceConn.connectedAt) / 1000
                appendLine("  - $address (we're ${if (deviceConn.isClient) "client" else "server"}, ${age}s, RSSI: ${deviceConn.rssi})")
            }
            appendLine()
            appendLine("Subscribed Devices (server mode): ${subscribedDevices.size}")
            appendLine()
            appendLine("Pending Connections: ${pendingConnections.size}")
            val now = System.currentTimeMillis()
            pendingConnections.forEach { (address, attempt) ->
                val elapsed = (now - attempt.lastAttempt) / 1000
                appendLine("  - $address: ${attempt.attempts} attempts, last ${elapsed}s ago")
            }
            appendLine()
            appendLine("Scan RSSI Cache: ${scanRSSI.size}")
            scanRSSI.forEach { (address, rssi) ->
                appendLine("  - $address: $rssi dBm")
            }
        }
    }
} 
