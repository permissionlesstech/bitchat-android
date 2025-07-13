package com.bitchat.android.mesh

import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.bitchat.android.protocol.BitchatPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*

/**
 * Manages GATT client operations, scanning, and client-side connections
 */
class BluetoothGattClientManager(
    private val context: Context,
    private val connectionScope: CoroutineScope,
    private val connectionTracker: BluetoothConnectionTracker,
    private val permissionManager: BluetoothPermissionManager,
    private val powerManager: PowerManager,
    private val delegate: BluetoothConnectionManagerDelegate?
) {
    
    companion object {
        private const val TAG = "BluetoothGattClientManager"
        // Use exact same UUIDs as iOS version
        private val SERVICE_UUID = UUID.fromString("F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C")
        private val CHARACTERISTIC_UUID = UUID.fromString("A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D")
    }
    
    // Core Bluetooth components
    private val bluetoothManager: BluetoothManager = 
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val bleScanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner
    
    // Scan management
    private var scanCallback: ScanCallback? = null
    
    // CRITICAL FIX: Scan rate limiting to prevent "scanning too frequently" errors
    private var lastScanStartTime = 0L
    private var lastScanStopTime = 0L
    private var isCurrentlyScanning = false
    private val scanRateLimit = 5000L // Minimum 5 seconds between scan start attempts
    
    // State management
    private var isActive = false
    
    /**
     * Start client manager
     */
    fun start(): Boolean {
        if (!permissionManager.hasBluetoothPermissions()) {
            Log.e(TAG, "Missing Bluetooth permissions")
            return false
        }
        
        if (bluetoothAdapter?.isEnabled != true) {
            Log.e(TAG, "Bluetooth is not enabled")
            return false
        }
        
        if (bleScanner == null) {
            Log.e(TAG, "BLE scanner not available")
            return false
        }
        
        isActive = true
        
        connectionScope.launch {
            if (powerManager.shouldUseDutyCycle()) {
                Log.i(TAG, "Using power-aware duty cycling")
            } else {
                startScanning()
            }
        }
        
        return true
    }
    
    /**
     * Stop client manager
     */
    fun stop() {
        isActive = false
        
        connectionScope.launch {
            stopScanning()
            Log.i(TAG, "GATT client manager stopped")
        }
    }
    
    /**
     * Handle scan state changes from power manager
     */
    fun onScanStateChanged(shouldScan: Boolean) {
        if (shouldScan) {
            startScanning()
        } else {
            stopScanning()
        }
    }
    
    /**
     * Start scanning with rate limiting
     */
    @Suppress("DEPRECATION")
    private fun startScanning() {
        if (!permissionManager.hasBluetoothPermissions() || bleScanner == null || !isActive) return
        
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
        
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        
        val scanFilters = listOf(scanFilter) 
        
        Log.d(TAG, "Starting BLE scan with target service UUID: $SERVICE_UUID")
        
        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
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
    
    /**
     * Stop scanning
     */
    @Suppress("DEPRECATION")
    private fun stopScanning() {
        if (!permissionManager.hasBluetoothPermissions() || bleScanner == null) return
        
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
    
    /**
     * Handle scan result and initiate connection if appropriate
     */
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
        
        // Power-aware RSSI filtering
        if (rssi < powerManager.getRSSIThreshold()) {
            Log.d(TAG, "Skipping device $deviceAddress due to weak signal: $rssi < ${powerManager.getRSSIThreshold()}")
            return
        }
        
        // Check if already connected OR already attempting to connect
        if (connectionTracker.isDeviceConnected(deviceAddress)) {
            return
        }
        
        // Check if connection attempt is allowed
        if (!connectionTracker.isConnectionAttemptAllowed(deviceAddress)) {
            Log.d(TAG, "Connection to $deviceAddress not allowed due to recent attempts")
            return
        }
        
        if (connectionTracker.isConnectionLimitReached()) {
            Log.d(TAG, "Connection limit reached (${powerManager.getMaxConnections()})")
            return
        }
        
        // Add pending connection and start connection
        if (connectionTracker.addPendingConnection(deviceAddress)) {
            connectToDevice(device, rssi)
        }
    }
    
    /**
     * Connect to a device as GATT client
     */
    @Suppress("DEPRECATION")
    private fun connectToDevice(device: BluetoothDevice, rssi: Int) {
        if (!permissionManager.hasBluetoothPermissions()) return
        
        val deviceAddress = device.address
        Log.i(TAG, "Connecting to bitchat device: $deviceAddress")
        
        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                Log.d(TAG, "Client: Connection state change - Device: $deviceAddress, Status: $status, NewState: $newState")

                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "Client: Successfully connected to $deviceAddress. Requesting MTU...")
                    // Request a larger MTU. Must be done before any data transfer.
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
                    
                    connectionTracker.cleanupDeviceConnection(deviceAddress)
                    
                    connectionScope.launch {
                        delay(500) // CLEANUP_DELAY
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
                    val deviceConn = BluetoothConnectionTracker.DeviceConnection(
                        device = gatt.device,
                        gatt = gatt,
                        rssi = rssi,
                        isClient = true
                    )
                    connectionTracker.addDeviceConnection(deviceAddress, deviceConn)
                    
                    // Start service discovery only AFTER MTU is set.
                    gatt.discoverServices()
                } else {
                    Log.w(TAG, "MTU negotiation failed for $deviceAddress with status: $status. Disconnecting.")
                    connectionTracker.removePendingConnection(deviceAddress)
                    gatt.disconnect()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {                
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val service = gatt.getService(SERVICE_UUID)
                    if (service != null) {
                        val characteristic = service.getCharacteristic(CHARACTERISTIC_UUID)
                        if (characteristic != null) {
                            connectionTracker.getDeviceConnection(deviceAddress)?.let { deviceConn ->
                                val updatedConn = deviceConn.copy(characteristic = characteristic)
                                connectionTracker.updateDeviceConnection(deviceAddress, updatedConn)
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
            Log.d(TAG, "Client: Attempting GATT connection to $deviceAddress with autoConnect=false")
            val gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) {
                Log.e(TAG, "connectGatt returned null for $deviceAddress")
                connectionTracker.removePendingConnection(deviceAddress)
            } else {
                Log.d(TAG, "Client: GATT connection initiated successfully for $deviceAddress")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Client: Exception connecting to $deviceAddress: ${e.message}")
            connectionTracker.removePendingConnection(deviceAddress)
        }
    }
    
    /**
     * Restart scanning for power mode changes
     */
    fun restartScanning() {
        if (!isActive) return
        
        connectionScope.launch {
            stopScanning()
            delay(1000) // Extra delay to avoid rate limiting
            
            if (powerManager.shouldUseDutyCycle()) {
                Log.i(TAG, "Switching to duty cycle scanning mode")
                // Duty cycle will handle scanning
            } else {
                Log.i(TAG, "Switching to continuous scanning mode")
                startScanning()
            }
        }
    }
} 