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
) {
    
    companion object {
        private const val TAG = "BluetoothConnectionTracker"
        private const val CONNECTION_RETRY_DELAY = com.bitchat.android.util.AppConstants.Mesh.CONNECTION_RETRY_DELAY_MS
        private const val MAX_CONNECTION_ATTEMPTS = com.bitchat.android.util.AppConstants.Mesh.MAX_CONNECTION_ATTEMPTS
        private const val CLEANUP_DELAY = com.bitchat.android.util.AppConstants.Mesh.CONNECTION_CLEANUP_DELAY_MS
        private const val CLEANUP_INTERVAL = com.bitchat.android.util.AppConstants.Mesh.CONNECTION_CLEANUP_INTERVAL_MS // 30 seconds
    }
    
    // Connection tracking - reduced memory footprint
    private val connectedDevices = ConcurrentHashMap<String, DeviceConnection>()
    private val subscribedDevices = CopyOnWriteArrayList<BluetoothDevice>()
    val addressPeerMap = ConcurrentHashMap<String, String>()
    // Track whether we have seen the first ANNOUNCE on a given device connection
    private val firstAnnounceSeen = ConcurrentHashMap<String, Boolean>()
    
    // RSSI tracking from scan results (for devices we discover but may connect as servers)
    private val scanRSSI = ConcurrentHashMap<String, Int>()
    
    // Connection attempt tracking with automatic cleanup
    private val pendingConnections = ConcurrentHashMap<String, ConnectionAttempt>()
    
    // State management
    private var isActive = false
    
    /**
     * Consolidated device connection information
     */
    data class DeviceConnection(
        val device: BluetoothDevice,
        val gatt: BluetoothGatt? = null,
        val characteristic: BluetoothGattCharacteristic? = null,
        val rssi: Int = Int.MIN_VALUE,
        val isClient: Boolean = false,
        val connectedAt: Long = System.currentTimeMillis()
    )
    
    /**
     * Connection attempt tracking with automatic expiry
     */
    data class ConnectionAttempt(
        val attempts: Int,
        val lastAttempt: Long = System.currentTimeMillis()
    ) {
        fun isExpired(): Boolean = 
            System.currentTimeMillis() - lastAttempt > CONNECTION_RETRY_DELAY * 2
        
        fun shouldRetry(): Boolean = 
            attempts < MAX_CONNECTION_ATTEMPTS && 
            System.currentTimeMillis() - lastAttempt > CONNECTION_RETRY_DELAY
    }
    
    /**
     * Start the connection tracker
     */
    fun start() {
        isActive = true
        startPeriodicCleanup()
    }
    
    /**
     * Stop the connection tracker
     */
    fun stop() {
        isActive = false
        cleanupAllConnections()
        clearAllConnections()
    }
    
    /**
     * Add a device connection
     */
    fun addDeviceConnection(deviceAddress: String, deviceConn: DeviceConnection) {
        Log.d(TAG, "Tracker: Adding device connection for $deviceAddress (isClient: ${deviceConn.isClient}")
        connectedDevices[deviceAddress] = deviceConn
        pendingConnections.remove(deviceAddress)
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
    fun isDeviceConnected(deviceAddress: String): Boolean {
        return connectedDevices.containsKey(deviceAddress)
    }
    
    /**
     * Check if connection attempt is allowed
     */
    fun isConnectionAttemptAllowed(deviceAddress: String): Boolean {
        val existingAttempt = pendingConnections[deviceAddress]
        return existingAttempt?.let { 
            it.isExpired() || it.shouldRetry() 
        } ?: true
    }
    
    /**
     * Add a pending connection attempt
     */
    fun addPendingConnection(deviceAddress: String): Boolean {
        Log.d(TAG, "Tracker: Adding pending connection for $deviceAddress")
        synchronized(pendingConnections) {
            // Double-check inside synchronized block
            val currentAttempt = pendingConnections[deviceAddress]
            if (currentAttempt != null && !currentAttempt.isExpired() && !currentAttempt.shouldRetry()) {
                Log.d(TAG, "Tracker: Connection attempt already in progress for $deviceAddress")
                return false
            }
            if (currentAttempt != null) {
                Log.d(TAG, "Tracker: current attempt: $currentAttempt")
            }
            
            // Update connection attempt atomically
            // If the previous attempt window expired, reset backoff to 1; otherwise increment
            val attempts = if (currentAttempt?.isExpired() == true) 1 else (currentAttempt?.attempts ?: 0) + 1
            pendingConnections[deviceAddress] = ConnectionAttempt(attempts)
            Log.d(TAG, "Tracker: Added pending connection for $deviceAddress (attempts: $attempts)")
            return true
        }
    }
    
    /**
     * Disconnect a specific device (by MAC address)
     */
    fun disconnectDevice(deviceAddress: String) {
        connectedDevices[deviceAddress]?.gatt?.let {
            try { it.disconnect() } catch (_: Exception) { }
        }
        cleanupDeviceConnection(deviceAddress)
        Log.d(TAG, "Requested disconnect for $deviceAddress")
    }

    /**
     * Remove a pending connection
     */
    fun removePendingConnection(deviceAddress: String) {
        pendingConnections.remove(deviceAddress)
    }
    
    /**
     * Get connected device count
     */
    fun getConnectedDeviceCount(): Int = connectedDevices.size
    
    /**
     * Check if connection limit is reached
     */
    fun isConnectionLimitReached(): Boolean {
        return connectedDevices.size >= powerManager.getMaxConnections()
    }
    
    /**
     * Enforce connection limits by disconnecting oldest connections
     */
    fun enforceConnectionLimits() {
        // Read debug overrides if available
        val dbg = try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance() } catch (_: Exception) { null }
        val maxOverall = dbg?.maxConnectionsOverall?.value ?: powerManager.getMaxConnections()
        val maxClient = dbg?.maxClientConnections?.value ?: maxOverall
        val maxServer = dbg?.maxServerConnections?.value ?: maxOverall

        val clients = connectedDevices.values.filter { it.isClient }
        val servers = connectedDevices.values.filter { !it.isClient }

        // Enforce client cap first (we can actively disconnect)
        if (clients.size > maxClient) {
            Log.i(TAG, "Enforcing client cap: ${clients.size} > $maxClient")
            val toDisconnect = clients.sortedBy { it.connectedAt }.take(clients.size - maxClient)
            toDisconnect.forEach { dc ->
                Log.d(TAG, "Disconnecting client ${dc.device.address} due to client cap")
                dc.gatt?.disconnect()
            }
        }

        // Note: server cap enforced in GattServerManager (we don't have server handle here)

        // Enforce overall cap by disconnecting oldest client connections
        if (connectedDevices.size > maxOverall) {
            Log.i(TAG, "Enforcing overall cap: ${connectedDevices.size} > $maxOverall")
            val excess = connectedDevices.size - maxOverall
            val toDisconnect = connectedDevices.values
                .filter { it.isClient } // only clients from here
                .sortedBy { it.connectedAt }
                .take(excess)
            toDisconnect.forEach { dc ->
                Log.d(TAG, "Disconnecting client ${dc.device.address} due to overall cap")
                dc.gatt?.disconnect()
            }
        }
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
     * Start periodic cleanup of expired connections
     */
    private fun startPeriodicCleanup() {
        connectionScope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL)
                
                if (!isActive) break
                
                try {
                    // Clean up expired pending connections
                    val expiredConnections = pendingConnections.filter { it.value.isExpired() }
                    expiredConnections.keys.forEach { pendingConnections.remove(it) }
                    
                    // Log cleanup if any
                    if (expiredConnections.isNotEmpty()) {
                        Log.d(TAG, "Cleaned up ${expiredConnections.size} expired connection attempts")
                    }
                    
                    // Log current state
                    Log.d(TAG, "Periodic cleanup: ${connectedDevices.size} connections, ${pendingConnections.size} pending")
                    
                } catch (e: Exception) {
                    Log.w(TAG, "Error in periodic cleanup: ${e.message}")
                }
            }
        }
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
