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
        private const val DEFAULT_ANNOUNCE_TIMEOUT_MS = 5000L
        private const val CLEANUP_DELAY = 500L
        private const val CLEANUP_INTERVAL = 30000L // 30 seconds
    }
    
    // Connection tracking - reduced memory footprint
    private val connectedDevices = ConcurrentHashMap<String, DeviceConnection>()
    private val subscribedDevices = CopyOnWriteArrayList<BluetoothDevice>()
    val addressPeerMap = ConcurrentHashMap<String, String>()
    
    // RSSI tracking from scan results (for devices we discover but may connect as servers)
    private val scanRSSI = ConcurrentHashMap<String, Int>()
    
    // Connection attempt tracking with automatic cleanup
    private val pendingConnections = ConcurrentHashMap<String, ConnectionAttempt>()
    // Track consecutive failures per device
    private val failureCounts = ConcurrentHashMap<String, Int>()
    // Devices to avoid reconnecting to (blacklist)
    private val avoidedDevices = ConcurrentHashMap<String, AvoidInfo>()
    // Announce watchdog jobs per device address
    private val announceWatchdogs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    // Configurable ANNOUNCE timeout (ms)
    @Volatile private var announceTimeoutMs: Long = DEFAULT_ANNOUNCE_TIMEOUT_MS
    
    // State management
    private var isActive = false
    // Optional callback to actively drop connections when a device is avoided
    @Volatile private var onDeviceAvoided: ((address: String, reason: String) -> Unit)? = null
    
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
     * Metadata for avoided devices
     */
    data class AvoidInfo(
        val reason: String,
        val since: Long = System.currentTimeMillis()
    )
    
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
        // Successful connection -> reset failure count and cancel any blacklist for this address
        failureCounts.remove(deviceAddress)
        // Do not auto-remove from avoidedDevices to keep defensive policy; only manual removal
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
        // Never attempt to connect to avoided devices
        if (avoidedDevices.containsKey(deviceAddress)) {
            Log.d(TAG, "Tracker: Device $deviceAddress is in avoid list; skipping attempts")
            return false
        }
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
            val attempts = (currentAttempt?.attempts ?: 0) + 1
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
     * Record a connection failure. If failures exceed threshold, avoid device.
     */
    fun recordConnectionFailure(deviceAddress: String, reason: String? = null) {
        val newCount = (failureCounts[deviceAddress] ?: 0) + 1
        failureCounts[deviceAddress] = newCount
        Log.w(TAG, "Tracker: Failure #$newCount for $deviceAddress${reason?.let { ": $it" } ?: ""}")
        if (newCount >= MAX_CONNECTION_ATTEMPTS) {
            addToAvoidList(deviceAddress, reason ?: "too_many_failures")
        }
    }

    /**
     * Add device to avoid list and cleanup its connection state.
     */
    fun addToAvoidList(deviceAddress: String, reason: String) {
        Log.w(TAG, "Tracker: Adding $deviceAddress to avoid list (reason: $reason)")
        avoidedDevices[deviceAddress] = AvoidInfo(reason)
        // Actively drop connection via callback if provided
        try { onDeviceAvoided?.invoke(deviceAddress, reason) } catch (_: Exception) { }
        // Ensure connection state is removed afterwards
        cleanupDeviceConnection(deviceAddress)
    }

    /**
     * Set the ANNOUNCE timeout in milliseconds (configurable).
     */
    fun setAnnounceTimeout(timeoutMs: Long) {
        announceTimeoutMs = timeoutMs.coerceAtLeast(1000L)
    }

    /** Set a callback invoked when a device is added to avoid list. */
    fun setOnDeviceAvoided(callback: (address: String, reason: String) -> Unit) {
        onDeviceAvoided = callback
    }

    /**
     * Start a watchdog that drops the connection if ANNOUNCE not mapped for this address.
     */
    fun startAnnounceWatchdog(deviceAddress: String) {
        // Cancel any existing watchdog first
        cancelAnnounceWatchdog(deviceAddress)
        val job = connectionScope.launch {
            val startedAt = System.currentTimeMillis()
            delay(announceTimeoutMs)
            // If no ANNOUNCE mapping exists and device still connected, drop and avoid
            val mapped = addressPeerMap.containsKey(deviceAddress)
            val stillConnected = connectedDevices.containsKey(deviceAddress) ||
                subscribedDevices.any { it.address == deviceAddress }
            if (!mapped && stillConnected) {
                Log.w(TAG, "Tracker: ANNOUNCE not received within ${announceTimeoutMs}ms for $deviceAddress; dropping + avoiding")
                addToAvoidList(deviceAddress, "announce_timeout")
            } else {
                Log.d(TAG, "Tracker: ANNOUNCE watchdog passed for $deviceAddress in ${System.currentTimeMillis() - startedAt}ms (mapped=$mapped, connected=$stillConnected)")
            }
        }
        announceWatchdogs[deviceAddress] = job
    }

    /** Cancel ANNOUNCE watchdog for an address. */
    fun cancelAnnounceWatchdog(deviceAddress: String) {
        announceWatchdogs.remove(deviceAddress)?.cancel()
    }

    /** Notify tracker that ANNOUNCE was received/mapped for this device. */
    fun markAnnounceReceived(deviceAddress: String, peerID: String) {
        // Map is updated externally; we just cancel watchdog here
        cancelAnnounceWatchdog(deviceAddress)
        Log.d(TAG, "Tracker: ANNOUNCE mapped for $deviceAddress -> $peerID; watchdog canceled")
    }

    /** Remove a single device from the avoid list (allow future attempts). */
    fun removeFromAvoidList(deviceAddress: String) {
        avoidedDevices.remove(deviceAddress)
        Log.d(TAG, "Tracker: Removed $deviceAddress from avoid list")
    }

    /** Clear the entire avoid list (dangerous; for debug UI). */
    fun clearAvoidList() {
        avoidedDevices.clear()
        Log.d(TAG, "Tracker: Cleared avoid list")
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
        pendingConnections.remove(deviceAddress)
        cancelAnnounceWatchdog(deviceAddress)
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
        failureCounts.clear()
        avoidedDevices.clear()
        announceWatchdogs.values.forEach { it.cancel() }
        announceWatchdogs.clear()
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
            appendLine("Failures: ${failureCounts.size}")
            failureCounts.forEach { (address, count) ->
                appendLine("  - $address: $count failures")
            }
            appendLine()
            appendLine("Avoid List: ${avoidedDevices.size}")
            avoidedDevices.forEach { (address, info) ->
                val age = (now - info.since) / 1000
                appendLine("  - $address: ${info.reason} (${age}s)")
            }
            appendLine()
            appendLine("Scan RSSI Cache: ${scanRSSI.size}")
            scanRSSI.forEach { (address, rssi) ->
                appendLine("  - $address: $rssi dBm")
            }
        }
    }

    /** Snapshot of avoided devices for debug UI */
    fun getAvoidedDevicesSnapshot(): Map<String, AvoidInfo> {
        return avoidedDevices.toMap()
    }
} 
