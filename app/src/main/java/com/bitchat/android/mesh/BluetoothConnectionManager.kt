package com.bitchat.android.mesh

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.ActivityCompat
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.SpecialRecipients
import com.bitchat.android.model.RoutedPacket
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Power-optimized Bluetooth connection manager with comprehensive memory management
 * Integrates with PowerManager for adaptive power consumption
 */
class BluetoothConnectionManager(
    private val context: Context, 
    private val myPeerID: String
) : PowerManagerDelegate {
    
    companion object {
        private const val TAG = "BluetoothConnectionManager"
        // Use exact same UUIDs as iOS version
        private val SERVICE_UUID = UUID.fromString("F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C")
        private val CHARACTERISTIC_UUID = UUID.fromString("A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D")
        
        // Connection management constants
        private const val CONNECTION_RETRY_DELAY = 5000L
        private const val MAX_CONNECTION_ATTEMPTS = 3
        private const val CLEANUP_DELAY = 500L
        private const val CLEANUP_INTERVAL = 30000L // 30 seconds
    }
    
    // Core Bluetooth components
    private val bluetoothManager: BluetoothManager = 
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val bleScanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner
    private val bleAdvertiser: BluetoothLeAdvertiser? = bluetoothAdapter?.bluetoothLeAdvertiser
    
    // Power management
    private val powerManager = PowerManager(context)
    
    // GATT server for peripheral mode
    private var gattServer: BluetoothGattServer? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    
    // Simplified connection tracking - reduced memory footprint
    private val connectedDevices = ConcurrentHashMap<String, DeviceConnection>()
    private val subscribedDevices = CopyOnWriteArrayList<BluetoothDevice>()
    public val addressPeerMap = ConcurrentHashMap<String, String>()
    
    // Connection attempt tracking with automatic cleanup
    private val pendingConnections = ConcurrentHashMap<String, ConnectionAttempt>()
    
    // Service state
    private var isActive = false
    private var scanCallback: ScanCallback? = null
    private var advertiseCallback: AdvertiseCallback? = null
    
    // CRITICAL FIX: Scan rate limiting to prevent "scanning too frequently" errors
    private var lastScanStartTime = 0L
    private var lastScanStopTime = 0L
    private var isCurrentlyScanning = false
    private val scanRateLimit = 5000L // Minimum 5 seconds between scan start attempts
    
    // Delegate for callbacks
    var delegate: BluetoothConnectionManagerDelegate? = null
    
    // Coroutines
    private val connectionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * Consolidated device connection information
     */
    private data class DeviceConnection(
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
    private data class ConnectionAttempt(
        val attempts: Int,
        val lastAttempt: Long = System.currentTimeMillis()
    ) {
        fun isExpired(): Boolean = 
            System.currentTimeMillis() - lastAttempt > CONNECTION_RETRY_DELAY * 2
        
        fun shouldRetry(): Boolean = 
            attempts < MAX_CONNECTION_ATTEMPTS && 
            System.currentTimeMillis() - lastAttempt > CONNECTION_RETRY_DELAY
    }
    
    init {
        powerManager.delegate = this
    }
    
    /**
     * Start all Bluetooth services with power optimization
     */
    fun startServices(): Boolean {
        Log.i(TAG, "Starting power-optimized Bluetooth services...")
        
        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "Missing Bluetooth permissions")
            return false
        }
        
        if (bluetoothAdapter?.isEnabled != true) {
            Log.e(TAG, "Bluetooth is not enabled")
            return false
        }
        
        if (bleScanner == null || bleAdvertiser == null) {
            Log.e(TAG, "BLE scanner or advertiser not available")
            return false
        }
        
        try {
            isActive = true
            
            // Setup GATT server first
            setupGattServer()
            
            // Start power manager and services
            connectionScope.launch {
                powerManager.start()
                delay(300) // Brief delay to ensure GATT server is ready
                
                startAdvertising()
                delay(100)
                
                if (powerManager.shouldUseDutyCycle()) {
                    Log.i(TAG, "Using power-aware duty cycling")
                } else {
                    startScanning()
                }
                
                startPeriodicCleanup()
                
                Log.i(TAG, "Power-optimized Bluetooth services started successfully")
            }
            
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Bluetooth services: ${e.message}")
            isActive = false
            return false
        }
    }
    
    /**
     * Stop all Bluetooth services with proper cleanup
     */
    fun stopServices() {
        Log.i(TAG, "Stopping power-optimized Bluetooth services")
        
        isActive = false
        
        connectionScope.launch {
            // Stop power manager first
            powerManager.stop()
            
            // Stop scanning and advertising  
            stopScanning()
            stopAdvertising()
            
            // Cleanup all GATT connections with delay
            cleanupAllConnections()
            
            // Close GATT server
            gattServer?.close()
            gattServer = null
            
            // Clear tracking
            clearAllConnections()
            
            connectionScope.cancel()
            
            Log.i(TAG, "All Bluetooth services stopped")
        }
    }
    
    /**
     * Set app background state for power optimization
     */
    fun setAppBackgroundState(inBackground: Boolean) {
        powerManager.setAppBackgroundState(inBackground)
    }
    
    // Function to send data to a single device (server side)
    private fun notifyDevice(device: BluetoothDevice, data: ByteArray): Boolean {
        return try {
            characteristic?.let { char ->
                char.value = data
                val result = gattServer?.notifyCharacteristicChanged(device, char, false) ?: false
                result
            } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "Error sending to server connection ${device.address}: ${e.message}")
            connectionScope.launch {
                delay(CLEANUP_DELAY)
                subscribedDevices.remove(device)
                addressPeerMap.remove(device.address)
            }
            false
        }
    }

    // Function to send data to a single device (client side)
    private fun writeToDeviceConn(deviceConn: DeviceConnection, data: ByteArray): Boolean {
        return try {
            deviceConn.characteristic?.let { char ->
                char.value = data
                val result = deviceConn.gatt?.writeCharacteristic(char) ?: false
                result
            } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "Error sending to client connection ${deviceConn.device.address}: ${e.message}")
            connectionScope.launch {
                delay(CLEANUP_DELAY)
                cleanupDeviceConnection(deviceConn.device.address)
            }
            false
        }
    }

    /**
     * Broadcast packet to connected devices with connection limit enforcement
     */
    fun broadcastPacket(routed: RoutedPacket) {
        val packet = routed.packet

        if (!isActive) return
        
        val data = packet.toBinaryData() ?: return
        
        if (packet.recipientID != SpecialRecipients.BROADCAST) {
            val recipientID = packet.recipientID?.let {
                String(it).replace("\u0000", "").trim()
            } ?: ""

            // Try to find the recipient in server connections (subscribedDevices)
            val targetDevice = subscribedDevices.firstOrNull { addressPeerMap[it.address] == recipientID }
            // If found, send directly
            if (targetDevice != null) {
                Log.d(TAG, "Send packet type ${packet.type} directly to target device for recipient $recipientID: ${targetDevice.address}")
                if (notifyDevice(targetDevice, data))
                    return  // Sent, no need to continue
            }

            // Try to find the recipient in client connections (connectedDevices)
            val targetDeviceConn = connectedDevices.values.firstOrNull { addressPeerMap[it.device.address] == recipientID }
            // If found, send directly
            if (targetDeviceConn != null) {
                Log.d(TAG, "Send packet type ${packet.type} directly to target client connection for recipient $recipientID: ${targetDeviceConn.device.address}")
                if (writeToDeviceConn(targetDeviceConn, data))
                    return  // Sent, no need to continue
            }
        }

        // Else, continue with broadcasting to all devices
        Log.d(TAG, "Broadcasting packet type ${packet.type} to ${subscribedDevices.size} server + ${connectedDevices.size} client connections")

        val senderID = String(packet.senderID).replace("\u0000", "")        
        // Send to server connections (devices connected to our GATT server)
        subscribedDevices.forEach { device ->
            if (device.address == routed.relayAddress) {
                Log.d(TAG, "Skipping broadcast back to relayer: ${device.address}")
                return@forEach
            }
            if (addressPeerMap[device.address] == senderID) {
                Log.d(TAG, "Skipping broadcast back to sender: ${device.address}")
                return@forEach
            }
            notifyDevice(device, data)
        }
        
        // Send to client connections
        connectedDevices.values.forEach { deviceConn ->
            if (deviceConn.isClient && deviceConn.gatt != null && deviceConn.characteristic != null) {
                if (deviceConn.device.address == routed.relayAddress) {
                    Log.d(TAG, "Skipping broadcast back to relayer: ${deviceConn.device.address}")
                    return@forEach
                }
                if (addressPeerMap[deviceConn.device.address] == senderID) {
                    Log.d(TAG, "Skipping broadcast back to sender: ${deviceConn.device.address}")
                    return@forEach
                }
                writeToDeviceConn(deviceConn, data)
            }
        }
    }
    
    /**
     * Get connected device count
     */
    fun getConnectedDeviceCount(): Int = connectedDevices.size
    
    /**
     * Get debug information including power management
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Power-Optimized Bluetooth Connection Manager ===")
            appendLine("Active: $isActive")
            appendLine("Bluetooth Enabled: ${bluetoothAdapter?.isEnabled}")
            appendLine("Has Permissions: ${hasBluetoothPermissions()}")
            appendLine("GATT Server Active: ${gattServer != null}")
            appendLine()
            appendLine(powerManager.getPowerInfo())
            appendLine()
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
    
    // MARK: - PowerManagerDelegate Implementation
    
    override fun onPowerModeChanged(newMode: PowerManager.PowerMode) {
        Log.i(TAG, "Power mode changed to: $newMode")
        
        connectionScope.launch {
            // CRITICAL FIX: Avoid rapid scan restarts by checking if we need to change scan behavior
            val wasUsingDutyCycle = powerManager.shouldUseDutyCycle()
            
            // Update advertising with new power settings
            stopAdvertising()
            delay(100)
            startAdvertising()
            
            // Only restart scanning if the duty cycle behavior changed
            val nowUsingDutyCycle = powerManager.shouldUseDutyCycle()
            if (wasUsingDutyCycle != nowUsingDutyCycle) {
                Log.d(TAG, "Duty cycle behavior changed (${wasUsingDutyCycle} -> ${nowUsingDutyCycle}), restarting scan")
                stopScanning()
                delay(1000) // Extra delay to avoid rate limiting
                
                if (nowUsingDutyCycle) {
                    Log.i(TAG, "Switching to duty cycle scanning mode")
                    // Duty cycle will handle scanning
                } else {
                    Log.i(TAG, "Switching to continuous scanning mode")
                    startScanning()
                }
            } else {
                Log.d(TAG, "Duty cycle behavior unchanged, keeping existing scan state")
            }
            
            // Enforce connection limits
            enforceConnectionLimits()
        }
    }
    
    override fun onScanStateChanged(shouldScan: Boolean) {
        if (shouldScan) {
            startScanning()
        } else {
            stopScanning()
        }
    }
    
    // MARK: - Private Implementation
    
    private fun hasBluetoothPermissions(): Boolean {
        val permissions = mutableListOf<String>()
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            permissions.addAll(listOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            ))
        } else {
            permissions.addAll(listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            ))
        }
        
        permissions.addAll(listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        ))
        
        return permissions.all { 
            ActivityCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED 
        }
    }
    
    @Suppress("DEPRECATION")
    private fun setupGattServer() {
        if (!hasBluetoothPermissions()) return
        
        val serverCallback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                // Guard against callbacks after service shutdown
                if (!isActive) {
                    Log.d(TAG, "Server: Ignoring connection state change after shutdown")
                    return
                }
                
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.d(TAG, "Server: Device connected ${device.address}")
                        val deviceConn = DeviceConnection(
                            device = device,
                            isClient = false
                        )
                        connectedDevices[device.address] = deviceConn
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.d(TAG, "Server: Device disconnected ${device.address}")
                        cleanupDeviceConnection(device.address)
                    }
                }
            }
            
            override fun onServiceAdded(status: Int, service: BluetoothGattService) {
                // Guard against callbacks after service shutdown
                if (!isActive) {
                    Log.d(TAG, "Server: Ignoring service added callback after shutdown")
                    return
                }
                
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Server: Service added successfully: ${service.uuid}")
                } else {
                    Log.e(TAG, "Server: Failed to add service: ${service.uuid}, status: $status")
                }
            }
            
            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                // Guard against callbacks after service shutdown
                if (!isActive) {
                    Log.d(TAG, "Server: Ignoring characteristic write after shutdown")
                    return
                }
                
                if (characteristic.uuid == CHARACTERISTIC_UUID) {
                    Log.d(TAG, "Server: Received packet from ${device.address}, size: ${value.size} bytes")
                    val packet = BitchatPacket.fromBinaryData(value)
                    if (packet != null) {
                        val peerID = String(packet.senderID).replace("\u0000", "")
                        Log.d(TAG, "Server: Parsed packet type ${packet.type} from $peerID")
                        delegate?.onPacketReceived(packet, peerID, device)
                    } else {
                        Log.w(TAG, "Server: Failed to parse packet from ${device.address}, size: ${value.size} bytes")
                        Log.w(TAG, "Server: Packet data: ${value.joinToString(" ") { "%02x".format(it) }}")
                    }
                    
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    }
                }
            }
            
            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                // Guard against callbacks after service shutdown
                if (!isActive) {
                    Log.d(TAG, "Server: Ignoring descriptor write after shutdown")
                    return
                }
                
                if (BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.contentEquals(value)) {
                    Log.d(TAG, "Device ${device.address} subscribed to notifications")
                    subscribedDevices.add(device)
                    
                    connectionScope.launch {
                        delay(100)
                        if (isActive) { // Check if still active
                            delegate?.onDeviceConnected(device)
                        }
                    }
                }
                
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        }
        
        // Proper cleanup sequencing to prevent race conditions
        gattServer?.let { server ->
            Log.d(TAG, "Cleaning up existing GATT server")
            try {
                server.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing existing GATT server: ${e.message}")
            }
        }
        
        // Small delay to ensure cleanup is complete
        Thread.sleep(100)
        
        if (!isActive) {
            Log.d(TAG, "Service inactive, skipping GATT server creation")
            return
        }
        
        // Create new server
        gattServer = bluetoothManager.openGattServer(context, serverCallback)
        
        // Create characteristic with notification support
        characteristic = BluetoothGattCharacteristic(
            CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or 
            BluetoothGattCharacteristic.PROPERTY_WRITE or 
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or 
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        
        val descriptor = BluetoothGattDescriptor(
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        characteristic?.addDescriptor(descriptor)
        
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(characteristic)
        
        gattServer?.addService(service)
        
        Log.i(TAG, "GATT server setup complete")
    }
    
    @Suppress("DEPRECATION")
    private fun startAdvertising() {
        if (!hasBluetoothPermissions() || bleAdvertiser == null || !isActive) return

        val settings = powerManager.getAdvertiseSettings()
        
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .setIncludeTxPowerLevel(false)
            .setIncludeDeviceName(false)
            .build()
        
        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Log.i(TAG, "Advertising started (power mode: ${powerManager.getPowerInfo().split("Current Mode: ")[1].split("\n")[0]})")
            }
            
            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "Advertising failed: $errorCode")
            }
        }
        
        try {
            bleAdvertiser.startAdvertising(settings, data, advertiseCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting advertising: ${e.message}")
        }
    }
    
    @Suppress("DEPRECATION")
    private fun stopAdvertising() {
        if (!hasBluetoothPermissions() || bleAdvertiser == null) return
        try {
            advertiseCallback?.let { bleAdvertiser.stopAdvertising(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping advertising: ${e.message}")
        }
    }
    
    @Suppress("DEPRECATION")
    private fun startScanning() {
        if (!hasBluetoothPermissions() || bleScanner == null || !isActive) return
        
        // CRITICAL FIX: Rate limit scan starts to prevent "scanning too frequently" errors
        val currentTime = System.currentTimeMillis()
        if (isCurrentlyScanning) {
            Log.d(TAG, "Scan already in progress, skipping start request")
            return
        }
        
        val timeSinceLastStart = currentTime - lastScanStartTime
        if (timeSinceLastStart < scanRateLimit) {
            val remainingWait = scanRateLimit - timeSinceLastStart
            Log.w(TAG, "Scan rate limited: need to wait ${remainingWait}ms before starting scan")
            
            // Schedule delayed scan start
            connectionScope.launch {
                delay(remainingWait)
                if (isActive && !isCurrentlyScanning) {
                    startScanning()
                }
            }
            return
        }
        
        // DIAGNOSTIC: Add both filtered and unfiltered scanning for debugging
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        

        val scanFilters = listOf(scanFilter) 
        
        Log.d(TAG, "Starting BLE scan with target service UUID: $SERVICE_UUID")
        
        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                // DEBUG: Log ALL scan results first
                val device = result.device
                val rssi = result.rssi
                val scanRecord = result.scanRecord
                Log.d(TAG, "Scan result: device: ${device.address}, Name: '${device.name}', RSSI: $rssi")
                handleScanResult(result)
            }
            
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                Log.d(TAG, "Batch scan results received: ${results.size} devices")
                results.forEach { result ->
                    handleScanResult(result)
                }
            }
            
            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed: $errorCode")
                isCurrentlyScanning = false
                lastScanStopTime = System.currentTimeMillis()
                
                when (errorCode) {
                    1 -> Log.e(TAG, "SCAN_FAILED_ALREADY_STARTED")
                    2 -> Log.e(TAG, "SCAN_FAILED_APPLICATION_REGISTRATION_FAILED") 
                    3 -> Log.e(TAG, "SCAN_FAILED_INTERNAL_ERROR")
                    4 -> Log.e(TAG, "SCAN_FAILED_FEATURE_UNSUPPORTED")
                    5 -> Log.e(TAG, "SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES")
                    6 -> {
                        Log.e(TAG, "SCAN_FAILED_SCANNING_TOO_FREQUENTLY")
                        Log.w(TAG, "Scan failed due to rate limiting - will retry after delay")
                        connectionScope.launch {
                            delay(10000) // Wait 10 seconds before retrying
                            if (isActive) {
                                startScanning()
                            }
                        }
                    }
                    else -> Log.e(TAG, "Unknown scan failure code: $errorCode")
                }
            }
        }
        
        try {
            lastScanStartTime = currentTime
            isCurrentlyScanning = true
            
            bleScanner.startScan(scanFilters, powerManager.getScanSettings(), scanCallback)
            Log.d(TAG, "BLE scan started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting scan: ${e.message}")
            isCurrentlyScanning = false
        }
    }
    
    @Suppress("DEPRECATION")
    private fun stopScanning() {
        if (!hasBluetoothPermissions() || bleScanner == null) return
        
        if (isCurrentlyScanning) {
            try {
                scanCallback?.let { 
                    bleScanner.stopScan(it)
                    Log.d(TAG, "BLE scan stopped successfully")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping scan: ${e.message}")
            }
            
            isCurrentlyScanning = false
            lastScanStopTime = System.currentTimeMillis()
        }
    }
    
    private fun handleScanResult(result: ScanResult) {
        val device = result.device
        val rssi = result.rssi
        val deviceAddress = device.address
        val scanRecord = result.scanRecord
        
        // CRITICAL: Only process devices that have our service UUID
        val hasOurService = scanRecord?.serviceUuids?.any { it.uuid == SERVICE_UUID } == true
        if (!hasOurService) {
            return
        }
        
        // FIXED: Extract peer ID from device name for iOS compatibility
        val deviceName = device.name
        val extractedPeerID = if (deviceName != null && deviceName.length == 8) {
            deviceName
        } else {
            null
        }
        
        Log.d(TAG, "Processing bitchat device: $deviceAddress, name: '$deviceName', peerID: $extractedPeerID, RSSI: $rssi")
        
        // Power-aware RSSI filtering
        if (rssi < powerManager.getRSSIThreshold()) {
            Log.d(TAG, "Skipping device $deviceAddress due to weak signal: $rssi < ${powerManager.getRSSIThreshold()}")
            return
        }
        
        // CRITICAL FIX: Prevent multiple simultaneous connections to same device
        // Check if already connected OR already attempting to connect
        if (connectedDevices.containsKey(deviceAddress)) {
            Log.d(TAG, "Device $deviceAddress already connected, skipping")
            return
        }
        
        // CRITICAL FIX: Check if connection attempt is already in progress
        val existingAttempt = pendingConnections[deviceAddress]
        if (existingAttempt != null && !existingAttempt.isExpired()) {
            if (!existingAttempt.shouldRetry()) {
                Log.d(TAG, "Connection to $deviceAddress already in progress or too many recent attempts (${existingAttempt.attempts})")
                return
            }
        }
        
        if (connectedDevices.size >= powerManager.getMaxConnections()) {
            Log.d(TAG, "Connection limit reached (${powerManager.getMaxConnections()})")
            return
        }
        
        // CRITICAL FIX: Use synchronized block to prevent race conditions
        synchronized(pendingConnections) {
            // Double-check inside synchronized block
            val currentAttempt = pendingConnections[deviceAddress]
            if (currentAttempt != null && !currentAttempt.isExpired() && !currentAttempt.shouldRetry()) {
                Log.d(TAG, "Connection to $deviceAddress blocked by concurrent attempt check")
                return
            }
            
            // Update connection attempt atomically
            val attempts = (currentAttempt?.attempts ?: 0) + 1
            pendingConnections[deviceAddress] = ConnectionAttempt(attempts)
            
            if (extractedPeerID != null) {
                Log.i(TAG, "Initiating connection to peer $extractedPeerID at $deviceAddress (RSSI: $rssi, attempt: $attempts)")
            } else {
                Log.i(TAG, "Initiating connection to device with bitchat service at $deviceAddress (RSSI: $rssi, attempt: $attempts)")
            }
            
            // Start connection immediately while holding lock
            connectToDevice(device, rssi)
        }
    }
    
    @Suppress("DEPRECATION")
    private fun connectToDevice(device: BluetoothDevice, rssi: Int) {
        if (!hasBluetoothPermissions()) return
        
        val deviceAddress = device.address
        
                val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                Log.d(TAG, "Client: Connection state change - Device: $deviceAddress, Status: $status, NewState: $newState")

                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "Client: Successfully connected to $deviceAddress. Requesting MTU...")
                    // FIX: Request a larger MTU. Must be done before any data transfer.
                    // 517 is the maximum supported MTU size on Android.
                    connectionScope.launch {
                        delay(200) // A small delay can improve reliability of MTU request.
                        gatt.requestMtu(517)
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.w(TAG, "Client: Disconnected from $deviceAddress with error status $status")
                        if (status == 147) {
                            Log.e(TAG, "Client: Connection establishment failed (status 147) for $deviceAddress")
                        }
                    } else {
                        Log.d(TAG, "Client: Cleanly disconnected from $deviceAddress")
                    }
                    
                    cleanupDeviceConnection(deviceAddress)
                    
                    connectionScope.launch {
                        delay(CLEANUP_DELAY)
                        try {
                            gatt.close()
                        } catch (e: Exception) {
                            Log.w(TAG, "Error closing GATT: ${e.message}")
                        }
                    }
                }
            }
            
            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                val deviceAddress = gatt.device.address
                Log.i(TAG, "Client: MTU changed for $deviceAddress to $mtu with status $status")

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "MTU successfully negotiated for $deviceAddress. Discovering services.")
                    
                    // Now that MTU is set, connection is fully ready.
                    val deviceConn = DeviceConnection(
                        device = gatt.device,
                        gatt = gatt,
                        rssi = rssi,
                        isClient = true
                    )
                    connectedDevices[deviceAddress] = deviceConn
                    pendingConnections.remove(deviceAddress)
                    
                    // Start service discovery only AFTER MTU is set.
                    gatt.discoverServices()
                } else {
                    Log.w(TAG, "MTU negotiation failed for $deviceAddress with status: $status. Disconnecting.")
                    pendingConnections.remove(deviceAddress)
                    gatt.disconnect()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                Log.d(TAG, "Client: Service discovery completed for $deviceAddress with status: $status")
                
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val service = gatt.getService(SERVICE_UUID)
                    if (service != null) {
                        val characteristic = service.getCharacteristic(CHARACTERISTIC_UUID)
                        if (characteristic != null) {
                            connectedDevices[deviceAddress]?.let { deviceConn ->
                                val updatedConn = deviceConn.copy(characteristic = characteristic)
                                connectedDevices[deviceAddress] = updatedConn
                                Log.d(TAG, "Client: Updated device connection with characteristic for $deviceAddress")
                            }
                            
                            gatt.setCharacteristicNotification(characteristic, true)
                            val descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                            if (descriptor != null) {
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                gatt.writeDescriptor(descriptor)
                                
                                connectionScope.launch {
                                    delay(200)
                                    Log.i(TAG, "Client: Connection setup complete for $deviceAddress")
                                    delegate?.onDeviceConnected(device)
                                }
                            } else {
                                Log.e(TAG, "Client: CCCD descriptor not found for $deviceAddress")
                                gatt.disconnect()
                            }
                        } else {
                            Log.e(TAG, "Client: Required characteristic not found for $deviceAddress")
                            gatt.disconnect()
                        }
                    } else {
                        Log.e(TAG, "Client: Required service not found for $deviceAddress")
                        gatt.disconnect()
                    }
                } else {
                    Log.e(TAG, "Client: Service discovery failed with status $status for $deviceAddress")
                    gatt.disconnect()
                }
            }
            
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                val value = characteristic.value
                Log.d(TAG, "Client: Received packet from ${gatt.device.address}, size: ${value.size} bytes")
                val packet = BitchatPacket.fromBinaryData(value)
                if (packet != null) {
                    val peerID = String(packet.senderID).replace("\u0000", "")
                    Log.d(TAG, "Client: Parsed packet type ${packet.type} from $peerID")
                    delegate?.onPacketReceived(packet, peerID, gatt.device)
                } else {
                    Log.w(TAG, "Client: Failed to parse packet from ${gatt.device.address}, size: ${value.size} bytes")
                    Log.w(TAG, "Client: Packet data: ${value.joinToString(" ") { "%02x".format(it) }}")
                }
            }
        }
        
        try {
            Log.d(TAG, "Attempting GATT connection to $deviceAddress with autoConnect=false")
            val gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) {
                Log.e(TAG, "connectGatt returned null for $deviceAddress")
                pendingConnections.remove(deviceAddress)
            } else {
                Log.d(TAG, "GATT connection initiated successfully for $deviceAddress")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception connecting to $deviceAddress: ${e.message}")
            pendingConnections.remove(deviceAddress)
        }
    }
    
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
    
    private fun enforceConnectionLimits() {
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
    
    private fun cleanupDeviceConnection(deviceAddress: String) {
        connectedDevices.remove(deviceAddress)?.let { deviceConn ->
            subscribedDevices.removeAll { it.address == deviceAddress }
            addressPeerMap.remove(deviceAddress)
        }
        // CRITICAL FIX: Always remove from pending connections when cleaning up
        // This prevents failed connections from blocking future attempts
        pendingConnections.remove(deviceAddress)
        Log.d(TAG, "Cleaned up device connection for $deviceAddress")
    }
    
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
    
    private fun clearAllConnections() {
        connectedDevices.clear()
        subscribedDevices.clear()
        addressPeerMap.clear()
        pendingConnections.clear()
    }
}

/**
 * Delegate interface for Bluetooth connection manager callbacks
 */
interface BluetoothConnectionManagerDelegate {
    fun onPacketReceived(packet: BitchatPacket, peerID: String, device: BluetoothDevice?)
    fun onDeviceConnected(device: BluetoothDevice)
}
