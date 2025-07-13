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
        private const val CONNECTION_RETRY_DELAY = 5000L
        private const val MAX_CONNECTION_ATTEMPTS = 3
        private const val CLEANUP_DELAY = 500L
        private const val CLEANUP_INTERVAL = 30000L // 30 seconds
    }
    
    // Connection tracking - reduced memory footprint
    private val connectedDevices = ConcurrentHashMap<String, DeviceConnection>()
    private val subscribedDevices = CopyOnWriteArrayList<BluetoothDevice>()
    val addressPeerMap = ConcurrentHashMap<String, String>()
    
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
        connectedDevices[deviceAddress] = deviceConn
        pendingConnections.remove(deviceAddress)
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
        synchronized(pendingConnections) {
            // Double-check inside synchronized block
            val currentAttempt = pendingConnections[deviceAddress]
            if (currentAttempt != null && !currentAttempt.isExpired() && !currentAttempt.shouldRetry()) {
                return false
            }
            
            // Update connection attempt atomically
            val attempts = (currentAttempt?.attempts ?: 0) + 1
            pendingConnections[deviceAddress] = ConnectionAttempt(attempts)
            return true
        }
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
        val maxConnections = powerManager.getMaxConnections()
        if (connectedDevices.size > maxConnections) {
            Log.i(TAG, "Enforcing connection limit: ${connectedDevices.size} > $maxConnections")
            
            // Disconnect oldest client connections first
            val sortedConnections = connectedDevices.values
                .filter { it.isClient }
                .sortedBy { it.connectedAt }
            
            val toDisconnect = sortedConnections.take(connectedDevices.size - maxConnections)
            toDisconnect.forEach { deviceConn ->
                Log.d(TAG, "Disconnecting ${deviceConn.device.address} due to connection limit")
                deviceConn.gatt?.disconnect()
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
        // CRITICAL FIX: Always remove from pending connections when cleaning up
        // This prevents failed connections from blocking future attempts
        pendingConnections.remove(deviceAddress)
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
                appendLine("  - $address (${if (deviceConn.isClient) "client" else "server"}, ${age}s, RSSI: ${deviceConn.rssi})")
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
        }
    }
} 