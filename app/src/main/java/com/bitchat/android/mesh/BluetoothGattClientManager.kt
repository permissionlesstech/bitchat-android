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
import com.bitchat.android.util.AppConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*
import kotlinx.coroutines.Job
import com.bitchat.android.ui.debug.DebugSettingsManager
import com.bitchat.android.ui.debug.DebugScanResult

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
        // Self-healing scan recovery tuning
        private const val SCAN_RETRY_BASE_MS = 3_000L          // base backoff for transient scan failures
        private const val SCAN_MAX_RETRY_DELAY_MS = 30_000L    // cap on backoff delay
        private const val SCAN_WATCHDOG_INTERVAL_MS = 30_000L  // how often to verify the scanner is alive
        private const val SCAN_STALE_RESULT_MS = 120_000L      // force a scan restart if no results for this long
    }
    
    // Core Bluetooth components
    private val bluetoothManager: BluetoothManager = 
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val bleScanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner

    private fun isBleTransportEnabled(): Boolean {
        return try {
            com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().bleEnabled.value
        } catch (_: Exception) {
            try { com.bitchat.android.ui.debug.DebugPreferenceManager.getBleEnabled(true) } catch (_: Exception) { true }
        }
    }

    private fun isClientRoleEnabled(): Boolean {
        return isBleTransportEnabled() &&
            (try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().gattClientEnabled.value } catch (_: Exception) { true })
    }
    
    /**
     * Public: Connect to a device by MAC address (for debug UI)
     */
    fun connectToAddress(deviceAddress: String): Boolean {
        if (!isClientRoleEnabled()) {
            Log.i(TAG, "connectToAddress skipped: BLE client disabled")
            return false
        }
        val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)
        return if (device != null) {
            val rssi = connectionTracker.getBestRSSI(deviceAddress) ?: -50
            connectToDevice(device, rssi)
            true
        } else {
            Log.w(TAG, "connectToAddress: No device for $deviceAddress")
            false
        }
    }

    // Scan management
    private var scanCallback: ScanCallback? = null
    
    // Scan rate limiting to prevent "scanning too frequently" errors
    private var lastScanStartTime = 0L
    private var lastScanStopTime = 0L
    @Volatile private var isCurrentlyScanning = false
    private val scanRateLimit = 5000L // Minimum 5 seconds between scan start attempts

    // Self-healing scan state.
    // scanningDesired distinguishes "we want to be scanning but it isn't running" (a fault to recover
    // from) from "scanning is intentionally off" (e.g. duty-cycle OFF window or client disabled).
    @Volatile private var scanningDesired = false
    @Volatile private var lastScanResultTime = 0L
    private var scanRetryCount = 0
    private var scanWatchdogJob: Job? = null
    
    // RSSI monitoring state
    private var rssiMonitoringJob: Job? = null
    
    // State management
    private var isActive = false
    
    /**
     * Start client manager
     */
    fun start(): Boolean {
        // Respect debug setting
        if (!isClientRoleEnabled()) {
            Log.i(TAG, "Client start skipped: BLE/GATT Client disabled in debug settings")
            return false
        }

        if (isActive) {
            Log.d(TAG, "GATT client already active; start is a no-op")
            return true
        }
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
                // Duty cycle drives onScanStateChanged(true/false); scanningDesired follows that.
            } else {
                scanningDesired = true
                startScanning()
            }
            
            // Start RSSI monitoring
            startRSSIMonitoring()
            // Start the scan watchdog so a silently-dead or wedged scanner self-heals.
            startScanWatchdog()
        }
        
        return true
    }
    
    /**
     * Stop client manager
     */
    fun stop() {
        scanningDesired = false
        stopScanWatchdog()
        if (!isActive) {
            // Idempotent stop
            stopScanning()
            stopRSSIMonitoring()
            Log.i(TAG, "GATT client manager stopped (already inactive)")
            return
        }

        isActive = false
        
        connectionScope.launch {
            // Disconnect all client connections decisively
            try {
                val conns = connectionTracker.getConnectedDevices().values.filter { it.isClient && it.gatt != null }
                conns.forEach { dc ->
                    try { dc.gatt?.disconnect() } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
            
            stopScanning()
            stopRSSIMonitoring()
            Log.i(TAG, "GATT client manager stopped")
        }
    }
    
    /**
     * Handle scan state changes from power manager
     */
    fun onScanStateChanged(shouldScan: Boolean) {
        val enabled = isClientRoleEnabled()
        scanningDesired = shouldScan && enabled
        if (shouldScan && enabled) {
            startScanning()
        } else {
            stopScanning()
        }
    }
    
    /**
     * Start periodic RSSI monitoring for all client connections
     */
    private fun startRSSIMonitoring() {
        rssiMonitoringJob?.cancel()
        rssiMonitoringJob = connectionScope.launch {
            while (isActive) {
                try {
                    // Request RSSI from all client connections
                    val connectedDevices = connectionTracker.getConnectedDevices()
                    connectedDevices.values.filter { it.isClient && it.gatt != null }.forEach { deviceConn ->
                        try {
                            Log.d(TAG, "Requesting RSSI from ${deviceConn.device.address}")
                            deviceConn.gatt?.readRemoteRssi()
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to request RSSI from ${deviceConn.device.address}: ${e.message}")
                        }
                    }
                    delay(AppConstants.Mesh.RSSI_UPDATE_INTERVAL_MS)
                } catch (e: Exception) {
                    Log.w(TAG, "Error in RSSI monitoring: ${e.message}")
                    delay(AppConstants.Mesh.RSSI_UPDATE_INTERVAL_MS)
                }
            }
        }
    }
    
    /**
     * Stop RSSI monitoring
     */
    private fun stopRSSIMonitoring() {
        rssiMonitoringJob?.cancel()
        rssiMonitoringJob = null
    }
    
    /**
     * Start scanning with rate limiting
     */
    @Suppress("DEPRECATION")
    private fun startScanning() {
        // Respect debug setting
        val enabled = isClientRoleEnabled()
        if (!permissionManager.hasBluetoothPermissions() || bleScanner == null || !isActive || !enabled) return
        
        // Rate limit scan starts to prevent "scanning too frequently" errors
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
                if (isActive && !isCurrentlyScanning && isClientRoleEnabled()) {
                    startScanning()
                }
            }
            return
        }
        
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(AppConstants.Mesh.Gatt.SERVICE_UUID))
            .build()
        
        val scanFilters = listOf(scanFilter) 
        
        Log.d(TAG, "Starting BLE scan with target service UUID: ${AppConstants.Mesh.Gatt.SERVICE_UUID}")
        
        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                // Log.d(TAG, "Scan result received: ${result.device.address}")
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
                    1 -> {
                        // Already started: the stack thinks a scan is running. Re-arm from a clean
                        // state so we don't stay wedged (stop then restart with backoff).
                        Log.e(TAG, "SCAN_FAILED_ALREADY_STARTED")
                        stopScanning()
                        scheduleScanRestart("already-started", SCAN_RETRY_BASE_MS)
                    }
                    2 -> {
                        // App registration failed: common transient stack fault. Previously had NO
                        // retry, which left discovery dead until a manual BLE toggle.
                        Log.e(TAG, "SCAN_FAILED_APPLICATION_REGISTRATION_FAILED")
                        scheduleScanRestart("registration-failed", SCAN_RETRY_BASE_MS)
                    }
                    3 -> {
                        Log.e(TAG, "SCAN_FAILED_INTERNAL_ERROR")
                        scheduleScanRestart("internal-error", SCAN_RETRY_BASE_MS)
                    }
                    4 -> Log.e(TAG, "SCAN_FAILED_FEATURE_UNSUPPORTED") // permanent: don't retry
                    5 -> {
                        // Out of hardware resources: back off longer so other scanners/connections
                        // can free up before we try again.
                        Log.e(TAG, "SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES")
                        scheduleScanRestart("out-of-resources", SCAN_RETRY_BASE_MS * 3)
                    }
                    6 -> {
                        Log.e(TAG, "SCAN_FAILED_SCANNING_TOO_FREQUENTLY")
                        Log.w(TAG, "Scan failed due to rate limiting - will retry after delay")
                        scheduleScanRestart("too-frequently", 10_000L)
                    }
                    else -> {
                        Log.e(TAG, "Unknown scan failure code: $errorCode")
                        scheduleScanRestart("unknown-$errorCode", SCAN_RETRY_BASE_MS)
                    }
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
     * Schedule a scan restart with incremental backoff. Used to recover from transient scan
     * failures that previously had no retry path (codes 2/3/5), leaving discovery dead until a
     * manual BLE toggle.
     */
    private fun scheduleScanRestart(reason: String, baseDelayMs: Long) {
        scanRetryCount++
        val delayMs = (baseDelayMs * scanRetryCount).coerceAtMost(SCAN_MAX_RETRY_DELAY_MS)
        Log.w(TAG, "Scheduling scan restart in ${delayMs}ms (attempt $scanRetryCount, reason=$reason)")
        connectionScope.launch {
            delay(delayMs)
            if (isActive && scanningDesired && isClientRoleEnabled() && !isCurrentlyScanning) {
                startScanning()
            }
        }
    }

    /**
     * Periodic watchdog that self-heals the scanner. Android can stop a scan without ever invoking
     * onScanFailed (internal stack reset, Doze, background throttling), which leaves the app
     * believing it is scanning while it is not. This re-arms the scanner in those cases.
     */
    private fun startScanWatchdog() {
        scanWatchdogJob?.cancel()
        scanWatchdogJob = connectionScope.launch {
            while (isActive) {
                delay(SCAN_WATCHDOG_INTERVAL_MS)
                try {
                    // Only act when we are supposed to be scanning. Honors duty-cycle OFF windows
                    // and the client-disabled state via scanningDesired.
                    if (!isActive || !scanningDesired || !isClientRoleEnabled()) continue
                    if (!permissionManager.hasBluetoothPermissions() || bluetoothAdapter?.isEnabled != true) continue

                    val now = System.currentTimeMillis()
                    if (!isCurrentlyScanning) {
                        Log.w(TAG, "Watchdog: scan desired but not running -> restarting scan")
                        startScanning()
                    } else if (lastScanResultTime > 0L &&
                        now - lastScanResultTime > SCAN_STALE_RESULT_MS &&
                        now - lastScanStartTime > SCAN_STALE_RESULT_MS) {
                        // We think we're scanning but haven't seen anything for a long time. The scan
                        // may have silently died (flag wedged true). Force a clean re-arm.
                        Log.w(TAG, "Watchdog: no scan results for ${(now - lastScanResultTime) / 1000}s -> forcing scan restart")
                        forceRestartScan()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Scan watchdog error: ${e.message}")
                }
            }
        }
    }

    private fun stopScanWatchdog() {
        scanWatchdogJob?.cancel()
        scanWatchdogJob = null
    }

    /**
     * Force a clean scan restart, clearing a possibly-wedged isCurrentlyScanning flag.
     */
    private fun forceRestartScan() {
        stopScanning()
        connectionScope.launch {
            delay(500)
            if (isActive && scanningDesired && isClientRoleEnabled() && !isCurrentlyScanning) {
                startScanning()
            }
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
        val hasOurService = scanRecord?.serviceUuids?.any { it.uuid == AppConstants.Mesh.Gatt.SERVICE_UUID } == true
        if (!hasOurService) {
            return
        }

        // Proof the scanner is alive and finding our network: refresh liveness and clear backoff.
        lastScanResultTime = System.currentTimeMillis()
        scanRetryCount = 0

        // Try to extract peerID from Service Data (if available) for stable identity
        val serviceData = scanRecord?.getServiceData(ParcelUuid(AppConstants.Mesh.Gatt.SERVICE_UUID))
        val peerID = if (serviceData != null && serviceData.size >= 8) {
            serviceData.joinToString("") { "%02x".format(it) }
        } else {
            null
        }

        if (peerID != null) {
            // Log.v(TAG, "Found peerID $peerID in scan record for $deviceAddress")
            if (connectionTracker.isPeerConnected(peerID)) {
                 Log.d(TAG, "Deduplication: Peer $peerID is already connected (ignoring $deviceAddress)")
                 return
            }
        }

        // Log.d(TAG, "Received scan result from $deviceAddress - already connected: ${connectionTracker.isDeviceConnected(deviceAddress)}")
        
        // Store RSSI from scan results for later use (especially for server connections)
        connectionTracker.updateScanRSSI(deviceAddress, rssi)

        // Publish scan result to debug UI buffer
        try {
            DebugSettingsManager.getInstance().addScanResult(
                DebugScanResult(
                    deviceName = device.name,
                    deviceAddress = deviceAddress,
                    rssi = rssi,
                    peerID = peerID // Use the discovered peerID if available
                )
            )
        } catch (_: Exception) { }
        
        // Power-aware RSSI filtering
        if (rssi < powerManager.getRSSIThreshold()) {
            Log.d(TAG, "Skipping device $deviceAddress due to weak signal: $rssi < ${powerManager.getRSSIThreshold()}")
            // Even if we skip connecting, still publish scan result to debug UI
            try {
                DebugSettingsManager.getInstance().addScanResult(
                    DebugScanResult(
                        deviceName = device.name,
                        deviceAddress = deviceAddress,
                        rssi = rssi,
                        peerID = peerID
                    )
                )
            } catch (_: Exception) { }
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
        
        // Check if connection limit is reached
        val dbg = try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance() } catch (_: Exception) { null }
        val maxOverall = dbg?.maxConnectionsOverall?.value ?: powerManager.getMaxConnections()
        val maxClient = dbg?.maxClientConnections?.value ?: maxOverall

        if (!connectionTracker.canConnectAsClient(maxOverall, maxClient)) {
            Log.d(TAG, "Client connection limit reached (overall: $maxOverall, client: $maxClient)")
            return
        }
        
        // Add pending connection and start connection
        if (connectionTracker.addPendingConnection(deviceAddress)) {
            connectToDevice(device, rssi, peerID)
        }
    }
    
    /**
     * Connect to a device as GATT client
     */
    @Suppress("DEPRECATION")
    private fun connectToDevice(device: BluetoothDevice, rssi: Int, peerID: String? = null) {
        if (!isClientRoleEnabled()) return
        if (!permissionManager.hasBluetoothPermissions()) return

        val deviceAddress = device.address
        Log.i(TAG, "Connecting to bitchat device: $deviceAddress (peerID: $peerID)")
        
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
                        connectionTracker.cleanupDeviceConnection(deviceAddress)
                    }

                    // Notify higher layers about device disconnection to update direct flags
                    delegate?.onDeviceDisconnected(gatt.device)

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
                        isClient = true,
                        peerID = peerID // Store the peerID discovered during scan
                    )
                    connectionTracker.addDeviceConnection(deviceAddress, deviceConn)
                    
                    // Start service discovery only AFTER MTU is set.
                    gatt.discoverServices()
                } else {
                    Log.w(TAG, "MTU negotiation failed for $deviceAddress with status: $status. Disconnecting.")
                    //connectionTracker.removePendingConnection(deviceAddress)
                    gatt.disconnect()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {                
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val service = gatt.getService(AppConstants.Mesh.Gatt.SERVICE_UUID)
                    if (service != null) {
                        val characteristic = service.getCharacteristic(AppConstants.Mesh.Gatt.CHARACTERISTIC_UUID)
                        if (characteristic != null) {
                            connectionTracker.getDeviceConnection(deviceAddress)?.let { deviceConn ->
                                val updatedConn = deviceConn.copy(characteristic = characteristic)
                                connectionTracker.updateDeviceConnection(deviceAddress, updatedConn)
                                Log.d(TAG, "Client: Updated device connection with characteristic for $deviceAddress")
                            }
                            
                            gatt.setCharacteristicNotification(characteristic, true)
                            val descriptor = characteristic.getDescriptor(AppConstants.Mesh.Gatt.DESCRIPTOR_UUID)
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
                Log.i(TAG, "Client: Received packet from ${gatt.device.address}, size: ${value.size} bytes")
                val packet = BitchatPacket.fromBinaryData(value)
                if (packet != null) {
                    val peerID = packet.senderID.take(8).toByteArray().joinToString("") { "%02x".format(it) }
                    Log.d(TAG, "Client: Parsed packet type ${packet.type} from $peerID")
                    delegate?.onPacketReceived(packet, peerID, gatt.device)
                } else {
                    Log.w(TAG, "Client: Failed to parse packet from ${gatt.device.address}, size: ${value.size} bytes")
                    Log.w(TAG, "Client: Packet data: ${value.joinToString(" ") { "%02x".format(it) }}")
                }
            }
            
            override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
                val deviceAddress = gatt.device.address
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Client: RSSI updated for $deviceAddress: $rssi dBm")
                    
                    // Update the connection tracker with new RSSI value
                    connectionTracker.getDeviceConnection(deviceAddress)?.let { deviceConn ->
                        val updatedConn = deviceConn.copy(rssi = rssi)
                        connectionTracker.updateDeviceConnection(deviceAddress, updatedConn)
                    }
                } else {
                    Log.w(TAG, "Client: Failed to read RSSI for $deviceAddress, status: $status")
                }
            }
        }
        
        try {
            Log.d(TAG, "Client: Attempting GATT connection to $deviceAddress with autoConnect=false")
            val gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) {
                Log.e(TAG, "connectGatt returned null for $deviceAddress")
                // keep the pending connection so we can avoid too many reconnections attempts, TODO: needs testing
                // connectionTracker.removePendingConnection(deviceAddress)
            } else {
                Log.d(TAG, "Client: GATT connection initiated successfully for $deviceAddress")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Client: Exception connecting to $deviceAddress: ${e.message}")
            // keep the pending connection so we can avoid too many reconnections attempts, TODO: needs testing
            // connectionTracker.removePendingConnection(deviceAddress)
        }
    }
    
    /**
     * Restart scanning for power mode changes
     */
    fun restartScanning() {
        // Respect debug setting
        val enabled = isClientRoleEnabled()
        if (!isActive || !enabled) return
        
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
