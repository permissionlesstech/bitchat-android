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
            Log.d(TAG, "connectToAddress skipped: BLE client disabled")
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
    private var scanDutyCycleJob: Job? = null
    
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
            applyPowerProfile(powerManager.profile.value)
        }
        
        return true
    }
    
    /**
     * Stop client manager
     */
    fun stop() {
        scanningDesired = false
        scanDutyCycleJob?.cancel()
        scanDutyCycleJob = null
        stopScanWatchdog()
        if (!isActive) {
            // Idempotent stop
            stopScanning()
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
            return
        }

        val timeSinceLastStart = currentTime - lastScanStartTime
        if (timeSinceLastStart < scanRateLimit) {
            val remainingWait = scanRateLimit - timeSinceLastStart
            Log.d(TAG, "Scan rate limited: waiting ${remainingWait}ms before starting scan")
            
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

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handleScanResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { result ->
                    handleScanResult(result)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                isCurrentlyScanning = false
                lastScanStopTime = System.currentTimeMillis()

                when (errorCode) {
                    1 -> {
                        // Already started: the stack thinks a scan is running. Re-arm from a clean
                        // state so we don't stay wedged (stop then restart with backoff).
                        stopScanning()
                        scheduleScanRestart("already-started", SCAN_RETRY_BASE_MS)
                    }
                    2 -> {
                        // App registration failed: common transient stack fault. Previously had NO
                        // retry, which left discovery dead until a manual BLE toggle.
                        scheduleScanRestart("registration-failed", SCAN_RETRY_BASE_MS)
                    }
                    3 -> {
                        scheduleScanRestart("internal-error", SCAN_RETRY_BASE_MS)
                    }
                    4 -> Unit // permanent: don't retry
                    5 -> {
                        // Out of hardware resources: back off longer so other scanners/connections
                        // can free up before we try again.
                        scheduleScanRestart("out-of-resources", SCAN_RETRY_BASE_MS * 3)
                    }
                    6 -> {
                        scheduleScanRestart("too-frequently", 10_000L)
                    }
                    else -> {
                        scheduleScanRestart("unknown-$errorCode", SCAN_RETRY_BASE_MS)
                    }
                }
                Log.e(TAG, "Scan failed: $errorCode")
            }
        }
        
        try {
            lastScanStartTime = currentTime
            isCurrentlyScanning = true
            
            bleScanner.startScan(scanFilters, powerManager.getScanSettings(), scanCallback)
            Log.i(TAG, "BLE scan started")
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
                    Log.i(TAG, "BLE scan stopped")
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
            if (connectionTracker.isPeerConnected(peerID)) {
                 return
            }
        }

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
            return
        }
        
        // Check if connection limit is reached
        val dbg = try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance() } catch (_: Exception) { null }
        val maxOverall = dbg?.maxConnectionsOverall?.value ?: powerManager.getMaxConnections()
        val maxClient = dbg?.maxClientConnections?.value ?: maxOverall

        if (!connectionTracker.canConnectAsClient(maxOverall, maxClient)) {
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
        val linkID = UUID.randomUUID().toString()
        Log.d(TAG, "Connecting to bitchat device: $deviceAddress (peerID: $peerID)")

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    // Request a larger MTU. Must be done before any data transfer.
                    connectionScope.launch {
                        delay(200) // A small delay can improve reliability of MTU request.
                        gatt.requestMtu(517)
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.w(TAG, "Disconnected from $deviceAddress with error status $status (client)")
                    } else {
                        Log.i(TAG, "Disconnected from $deviceAddress (client)")
                    }
                    // Capture the observed peer before cleanup drops the address mapping.
                    val disconnectedPeerID = connectionTracker.addressPeerMap[deviceAddress]
                    connectionTracker.cleanupDeviceConnectionIfCurrent(deviceAddress, linkID)

                    // Notify higher layers about device disconnection to update direct flags
                    delegate?.onDeviceDisconnected(gatt.device, linkID, disconnectedPeerID)

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

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    // Now that MTU is set, connection is fully ready.
                    val deviceConn = BluetoothConnectionTracker.DeviceConnection(
                        device = gatt.device,
                        gatt = gatt,
                        rssi = rssi,
                        isClient = true,
                        peerID = peerID, // Store the peerID discovered during scan
                        linkID = linkID
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
                            if (connectionTracker.updateDeviceConnectionIfCurrent(
                                    deviceAddress,
                                    linkID
                                ) { it.copy(characteristic = characteristic) }
                            ) {
                                // Characteristic stored on the current device connection
                            }
                            
                            gatt.setCharacteristicNotification(characteristic, true)
                            val descriptor = characteristic.getDescriptor(AppConstants.Mesh.Gatt.DESCRIPTOR_UUID)
                            if (descriptor != null) {
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                gatt.writeDescriptor(descriptor)
                                
                                connectionScope.launch {
                                    delay(200)
                                    Log.i(TAG, "Connected to $deviceAddress (client)")
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
                val packet = BitchatPacket.fromBinaryData(value)
                if (packet != null) {
                    val peerID = packet.senderID.take(8).toByteArray().joinToString("") { "%02x".format(it) }
                    delegate?.onPacketReceived(packet, peerID, gatt.device, linkID)
                } else {
                    Log.d(TAG, "Failed to parse packet from ${gatt.device.address}, size: ${value.size} bytes")
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (characteristic.uuid == AppConstants.Mesh.Gatt.CHARACTERISTIC_UUID) {
                    delegate?.onGattClientWriteComplete(gatt.device.address, linkID, status)
                }
            }
            
        }
        
        try {
            val gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) {
                Log.e(TAG, "connectGatt returned null for $deviceAddress")
                // keep the pending connection so we can avoid too many reconnections attempts, TODO: needs testing
                // connectionTracker.removePendingConnection(deviceAddress)
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
            applyPowerProfile(powerManager.profile.value)
        }
    }

    /**
     * Apply the current process-wide profile without ever disabling background discovery.
     */
    fun applyPowerProfile(profile: PowerManager.RuntimePerformanceProfile) {
        scanDutyCycleJob?.cancel()
        scanDutyCycleJob = null
        if (!isActive || !isClientRoleEnabled()) {
            onScanStateChanged(false)
            return
        }

        if (profile.ble.continuousScan) {
            startScanWatchdog()
            onScanStateChanged(true)
            return
        }

        // Duty-cycled scans are re-armed every window, so the continuous-scan watchdog would only
        // create background wakeups during intentional OFF periods.
        stopScanWatchdog()
        scanDutyCycleJob = connectionScope.launch {
            while (isActive && isClientRoleEnabled()) {
                onScanStateChanged(true)
                delay(profile.ble.scanOnMs)
                if (!isActive || !isClientRoleEnabled()) break
                onScanStateChanged(false)
                delay(profile.ble.scanOffMs)
            }
        }
    }
} 
