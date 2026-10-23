package com.bitchat.android.mesh

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.util.AppConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages GATT server operations, advertising, and server-side connections
 */
class BluetoothGattServerManager(
    private val context: Context,
    private val connectionScope: CoroutineScope,
    private val connectionTracker: BluetoothConnectionTracker,
    private val permissionManager: BluetoothPermissionManager,
    private val powerManager: PowerManager,
    private val delegate: BluetoothConnectionManagerDelegate?,
    private val myPeerID: String
) {
    
    companion object {
        private const val TAG = "BluetoothGattServerManager"
        // Self-healing advertising recovery tuning
        private const val ADVERTISE_RETRY_BASE_MS = 3_000L      // base backoff for transient advertise failures
        private const val ADVERTISE_MAX_RETRY_DELAY_MS = 30_000L // cap on backoff delay
        // ADAPTIVE on incoming links: evaluation cadence
        private const val ADAPTIVE_EVAL_INTERVAL_MS = 2_000L
        // A remote that moves a link back to 1M on its own is trusted for this long
        private const val REMOTE_CHOICE_HOLD_MS = 60_000L
        private const val OWN_REQUEST_WINDOW_MS = 5_000L
    }

    /**
     * Incoming (server-side) link state for ADAPTIVE. Android has no RSSI read on the server side.
     * Advertisement RSSI is not usable as a proxy: it depends on the remote's advertising power,
     * which changes with its power mode (measured 15-25 dB below link RSSI between two phones).
     * Instead the link follows our own outgoing connection to the same peer (same radio path), plus
     * send failures. This side only ever moves a link *to* Coded; returning to 1M is left to the
     * side that opened the connection, so the two ends never pull a link in opposite directions.
     */
    private class ServerPhyState(val device: BluetoothDevice, val linkID: String) {
        val adaptive = AdaptivePhyController { BleAdaptiveSettings.current() }
        @Volatile var txPhy: Int = 0
        @Volatile var rxPhy: Int = 0
        @Volatile var lastRequestAt: Long = 0L
        @Volatile var remoteChose1mUntil: Long = 0L
        val backoff = PhyRequestBackoff()
        val isCoded: Boolean get() = txPhy == BluetoothDevice.PHY_LE_CODED && rxPhy == BluetoothDevice.PHY_LE_CODED
    }

    private val serverPhyStates = ConcurrentHashMap<String, ServerPhyState>()
    private var adaptiveJob: kotlinx.coroutines.Job? = null

    /** Set by the connection manager: true when [address] is our own outgoing connection. */
    var isOwnOutgoing: (String) -> Boolean = { false }

    /** Set by the connection manager: smoothed link RSSI of our outgoing connection to a peer. */
    var outgoingLinkRssi: (String) -> Int? = { null }
    
    // Core Bluetooth components
    private val bluetoothManager: BluetoothManager = 
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val bleAdvertiser: BluetoothLeAdvertiser? = bluetoothAdapter?.bluetoothLeAdvertiser
    
    // GATT server for peripheral mode
    private var gattServer: BluetoothGattServer? = null
    private val serverLinkIDs = ConcurrentHashMap<String, String>()
    private var characteristic: BluetoothGattCharacteristic? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var advertiseRetryCount = 0
    private var codedAdvertisingCallback: AdvertisingSetCallback? = null
    private var codedAdvertiseRetryCount = 0
    
    // State management
    private var isActive = false

    private fun isBleTransportEnabled(): Boolean {
        return try {
            com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().bleEnabled.value
        } catch (_: Exception) {
            try { com.bitchat.android.ui.debug.DebugPreferenceManager.getBleEnabled(true) } catch (_: Exception) { true }
        }
    }

    private fun longRangeMode(): BleLongRangeMode =
        try {
            com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().bleLongRangeMode.value
        } catch (_: Exception) {
            BleLongRangeMode.DEFAULT
        }

    private fun codedCoding(): BleCodedCoding =
        try {
            com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().bleCodedCoding.value
        } catch (_: Exception) {
            BleCodedCoding.DEFAULT
        }

    private fun capabilities(): BleRadioCapabilities =
        BleRadioCapabilities.detect(bluetoothAdapter).also { BleLinkDiagnostics.setCapabilities(it) }

    private fun isServerRoleEnabled(): Boolean {
        return isBleTransportEnabled() &&
            (try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().gattServerEnabled.value } catch (_: Exception) { true })
    }

    /**
     * Disconnect a specific device (used by ConnectionManager to enforce overall limits)
     */
    fun disconnectDevice(device: BluetoothDevice) {
        try {
            gattServer?.cancelConnection(device)
        } catch (e: Exception) {
            Log.w(TAG, "Error disconnecting device ${device.address}: ${e.message}")
        }
    }
    
    /**
     * Start GATT server
     */
    fun start(): Boolean {
        // Respect debug setting
        if (!isServerRoleEnabled()) {
            Log.i(TAG, "Server start skipped: BLE/GATT Server disabled in debug settings")
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
        
        if (bleAdvertiser == null) {
            Log.e(TAG, "BLE advertiser not available")
            return false
        }
        
        isActive = true

        connectionScope.launch {
            setupGattServer()
            delay(300) // Brief delay to ensure GATT server is ready
            startAdvertising()
        }
        startAdaptiveLoop()

        return true
    }
    
    /**
     * Stop GATT server
     */
    fun stop() {
        if (!isActive) {
            // Idempotent stop
            stopAdvertising()
            // Ensure server is closed if present
            gattServer?.close()
            gattServer = null
            serverLinkIDs.clear()
            serverPhyStates.clear()
            return
        }

        isActive = false
        adaptiveJob?.cancel()
        adaptiveJob = null
        serverPhyStates.clear()

        connectionScope.launch {
            stopAdvertising()
            
            // Try to cancel any active connections explicitly before closing
            try {
                // Disconnect ALL server connections
                val servers = connectionTracker.getConnectedDevices().values.filter { !it.isClient }
                servers.forEach { d ->
                    try { gattServer?.cancelConnection(d.device) } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
            
            // Close GATT server
            gattServer?.close()
            gattServer = null
            serverLinkIDs.clear()
            
            Log.i(TAG, "GATT server stopped")
        }
    }
    
    /**
     * Get GATT server instance
     */
    fun getGattServer(): BluetoothGattServer? = gattServer
    
    /**
     * Get characteristic instance
     */
    fun getCharacteristic(): BluetoothGattCharacteristic? = characteristic
    
    /**
     * Setup GATT server with proper sequencing
     */
    @Suppress("DEPRECATION")
    private fun setupGattServer() {
        if (!permissionManager.hasBluetoothPermissions()) return
        
        val serverCallback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                // Guard against callbacks after service shutdown
                if (!isActive) {
                    return
                }

                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.i(TAG, "Connected to ${device.address} (server)")
                        val linkID = UUID.randomUUID().toString()
                        serverLinkIDs[device.address] = linkID
                        
                        // Get best available RSSI (scan RSSI for server connections)
                        val rssi = connectionTracker.getBestRSSI(device.address) ?: Int.MIN_VALUE
                        
                        val deviceConn = BluetoothConnectionTracker.DeviceConnection(
                            device = device,
                            rssi = rssi,
                            isClient = false,
                            linkID = linkID
                        )
                        connectionTracker.addDeviceConnection(device.address, deviceConn)
                        BleLinkDiagnostics.onLinkOpened(
                            linkID, device.address, isClient = false, discoveredPhy = null,
                            rssi = rssi.takeIf { it != Int.MIN_VALUE }
                        )
                        BleLinkDiagnostics.onLinkState(linkID, "connected")
                        serverPhyStates[linkID] = ServerPhyState(device, linkID)
                        readServerPhy(device)

                        connectionScope.launch {
                            delay(1000)
                            if (isActive) { // Check if still active
                                requestLinkPhy(device, linkID)
                                delegate?.onDeviceConnected(device)
                            }
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.i(TAG, "Disconnected from ${device.address} (server)")
                        val linkID = serverLinkIDs.remove(device.address)
                        linkID?.let { serverPhyStates.remove(it) }
                        BleLinkDiagnostics.onLinkClosed(linkID, device.address, status)
                        // Capture the observed peer before cleanup drops the address mapping.
                        val disconnectedPeerID = connectionTracker.addressPeerMap[device.address]
                        if (linkID != null) {
                            connectionTracker.cleanupDeviceConnectionIfCurrent(device.address, linkID)
                        }
                        // Notify delegate about device disconnection so higher layers can update direct flags
                        delegate?.onDeviceDisconnected(device, linkID, disconnectedPeerID)
                    }
                }
            }
            
            override fun onServiceAdded(status: Int, service: BluetoothGattService) {
                // Guard against callbacks after service shutdown
                if (!isActive) {
                    return
                }

                if (status != BluetoothGatt.GATT_SUCCESS) {
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
                    return
                }

                if (characteristic.uuid == AppConstants.Mesh.Gatt.CHARACTERISTIC_UUID) {
                    val linkID = serverLinkIDs[device.address]
                    if (linkID == null) {
                        Log.d(TAG, "Server: Dropping packet from stale connection ${device.address}")
                        if (responseNeeded) {
                            gattServer?.sendResponse(
                                device,
                                requestId,
                                BluetoothGatt.GATT_FAILURE,
                                0,
                                null
                            )
                        }
                        return
                    }
                    val packet = BitchatPacket.fromBinaryData(value)
                    BleLinkDiagnostics.onReceived(linkID)
                    if (packet != null) {
                        val peerID = packet.senderID.take(8).toByteArray().joinToString("") { "%02x".format(it) }
                        delegate?.onPacketReceived(packet, peerID, device, linkID)
                    } else {
                        Log.d(TAG, "Server: Failed to parse packet from ${device.address}, size: ${value.size} bytes")
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
                    return
                }

                if (BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.contentEquals(value)) {
                    connectionTracker.addSubscribedDevice(device)

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

            override fun onPhyUpdate(device: BluetoothDevice, txPhy: Int, rxPhy: Int, status: Int) {
                serverLinkIDs[device.address]?.let { linkID ->
                    serverPhyStates[linkID]?.let { state ->
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            val now = System.currentTimeMillis()
                            // The remote moved the link off Coded without us asking: respect its choice.
                            if (state.isCoded && txPhy != BluetoothDevice.PHY_LE_CODED &&
                                now - state.lastRequestAt > OWN_REQUEST_WINDOW_MS
                            ) {
                                state.remoteChose1mUntil = now + REMOTE_CHOICE_HOLD_MS
                            }
                            state.txPhy = txPhy
                            state.rxPhy = rxPhy
                            state.backoff.onPhy(state.isCoded)
                        }
                    }
                    BleLinkDiagnostics.onPhy(linkID, txPhy, rxPhy, status, fromUpdate = true)
                }
            }

            override fun onPhyRead(device: BluetoothDevice, txPhy: Int, rxPhy: Int, status: Int) {
                serverLinkIDs[device.address]?.let { linkID ->
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        serverPhyStates[linkID]?.let {
                            it.txPhy = txPhy
                            it.rxPhy = rxPhy
                            it.backoff.onPhy(it.isCoded)
                        }
                    }
                    BleLinkDiagnostics.onPhy(linkID, txPhy, rxPhy, status, fromUpdate = false)
                }
            }

            override fun onNotificationSent(device: BluetoothDevice, status: Int) {
                delegate?.onGattServerNotificationComplete(
                    device.address,
                    serverLinkIDs[device.address],
                    status
                )
            }
        }
        
        // Proper cleanup sequencing to prevent race conditions
        gattServer?.let { server ->
            try {
                server.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing existing GATT server: ${e.message}")
            }
        }

        // Small delay to ensure cleanup is complete
        Thread.sleep(100)

        if (!isActive) {
            return
        }
        
        // Create new server
        gattServer = bluetoothManager.openGattServer(context, serverCallback)
        
        // Create characteristic with notification support
        characteristic = BluetoothGattCharacteristic(
            AppConstants.Mesh.Gatt.CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or 
            BluetoothGattCharacteristic.PROPERTY_WRITE or 
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or 
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        
        val descriptor = BluetoothGattDescriptor(
            AppConstants.Mesh.Gatt.DESCRIPTOR_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        characteristic?.addDescriptor(descriptor)
        
        val service = BluetoothGattService(AppConstants.Mesh.Gatt.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(characteristic)
        
        gattServer?.addService(service)
        
        Log.i(TAG, "GATT server setup complete")
    }
    
    /**
     * Start advertising
     */
    @Suppress("DEPRECATION")
    private fun startAdvertising() {
        // Respect debug setting
        val enabled = isServerRoleEnabled()

        // Guard conditions – never throw here to avoid crashing the app from a background coroutine
        if (!permissionManager.hasBluetoothPermissions()) {
            Log.w(TAG, "Not starting advertising: missing Bluetooth permissions")
            return
        }
        if (bluetoothAdapter == null) {
            Log.w(TAG, "Not starting advertising: bluetoothAdapter is null")
            return
        }
        if (!isActive) {
            return
        }
        if (!enabled) {
            Log.d(TAG, "Not starting advertising: GATT Server disabled via debug settings")
            return
        }
        if (bleAdvertiser == null) {
            Log.w(TAG, "Not starting advertising: BLE advertiser not available on this device")
            return
        }
        if (!bluetoothAdapter.isMultipleAdvertisementSupported) {
            Log.w(TAG, "Not starting advertising: multiple advertisement not supported on this device")
            return
        }

        val mode = longRangeMode()
        val settings = powerManager.getAdvertiseSettings(longRange = mode != BleLongRangeMode.OFF)
        
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(AppConstants.Mesh.Gatt.SERVICE_UUID))
            .setIncludeTxPowerLevel(false)
            .setIncludeDeviceName(false)
            .build()
            
        // Add stable identity (first 8 bytes of peerID) to Scan Response
        // This allows scanners to deduplicate devices even if MAC address rotates
        val peerIDBytes = peerIDAdvertisementBytes()
        
        val scanResponse = AdvertiseData.Builder()
            .addServiceData(ParcelUuid(AppConstants.Mesh.Gatt.SERVICE_UUID), peerIDBytes)
            .setIncludeTxPowerLevel(false)
            .setIncludeDeviceName(false)
            .build()

        // A restart can race the initial start (e.g. power profile change during startup). Stop the
        // previous advertisement before replacing its callback, or it can never be stopped again and
        // keeps occupying a controller advertising slot.
        advertiseCallback?.let { previous ->
            try { bleAdvertiser.stopAdvertising(previous) } catch (_: Exception) { }
        }

        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                advertiseRetryCount = 0
                BleLinkDiagnostics.onLegacyAdvertiser(BleLinkDiagnostics.AdvertiserState.ACTIVE)
                val mode = try {
                    powerManager.getPowerInfo().split("Current Mode: ")[1].split("\n")[0]
                } catch (_: Exception) { "unknown" }
                Log.i(TAG, "Advertising started (power mode: $mode)")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "Advertising failed: $errorCode")
                BleLinkDiagnostics.onLegacyAdvertiser(BleLinkDiagnostics.AdvertiserState.FAILED, errorCode)
                // Previously this only logged, so if advertising failed this device became
                // undiscoverable until a manual BLE toggle. Retry transient failures with backoff.
                when (errorCode) {
                    ADVERTISE_FAILED_ALREADY_STARTED -> Unit // already advertising, no retry
                    ADVERTISE_FAILED_DATA_TOO_LARGE -> Unit // config issue, not retrying
                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> Unit // unsupported, not retrying
                    ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> {
                        scheduleAdvertiseRestart("too-many-advertisers")
                    }
                    ADVERTISE_FAILED_INTERNAL_ERROR -> {
                        scheduleAdvertiseRestart("internal-error")
                    }
                    else -> {
                        scheduleAdvertiseRestart("unknown-$errorCode")
                    }
                }
            }
        }
        
        try {
            BleLinkDiagnostics.onLegacyAdvertiser(BleLinkDiagnostics.AdvertiserState.STARTING)
            bleAdvertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException starting advertising (missing permission?): ${se.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting advertising: ${e.message}")
        }

        startCodedAdvertising(mode, peerIDBytes)
    }

    private fun peerIDAdvertisementBytes(): ByteArray = try {
        myPeerID.chunked(2).map { it.toInt(16).toByte() }.toByteArray().take(8).toByteArray()
    } catch (e: Exception) {
        ByteArray(0)
    }

    /**
     * Second, independent advertising set on LE Coded (primary and secondary PHY) so Coded-capable
     * scanners can discover and connect at long range. It runs alongside the legacy advertisement,
     * which stays the compatibility path; any failure here leaves legacy advertising untouched.
     *
     * Extended connectable advertisements cannot be scannable, so the peer ID that the legacy path
     * carries in the scan response is placed directly in this advertisement.
     */
    private fun startCodedAdvertising(mode: BleLongRangeMode, peerIDBytes: ByteArray) {
        val advertiser = bleAdvertiser ?: return
        val caps = capabilities()
        BleLinkDiagnostics.setMode(mode)
        if (mode == BleLongRangeMode.OFF) {
            BleLinkDiagnostics.onCodedAdvertiser(BleLinkDiagnostics.AdvertiserState.DISABLED)
            return
        }
        if (!BleLongRangePolicy.shouldAdvertiseCoded(mode, caps)) {
            BleLinkDiagnostics.onCodedAdvertiser(BleLinkDiagnostics.AdvertiserState.UNSUPPORTED)
            return
        }
        if (codedAdvertisingCallback != null) return

        val tuning = BleLongRangePolicy.codedAdvertisingParams(powerManager.profile.value.mode)
        val parameters = AdvertisingSetParameters.Builder()
            .setLegacyMode(false)
            .setConnectable(true)
            .setScannable(false)
            .setPrimaryPhy(BluetoothDevice.PHY_LE_CODED)
            .setSecondaryPhy(BluetoothDevice.PHY_LE_CODED)
            .setInterval(tuning.interval.also { BleLinkDiagnostics.onCodedAdvertisingInterval(it) })
            .setTxPowerLevel(tuning.txPowerLevel)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(AppConstants.Mesh.Gatt.SERVICE_UUID))
            .addServiceData(ParcelUuid(AppConstants.Mesh.Gatt.SERVICE_UUID), peerIDBytes)
            .setIncludeTxPowerLevel(false)
            .setIncludeDeviceName(false)
            .build()

        val callback = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
                if (status == ADVERTISE_SUCCESS) {
                    codedAdvertiseRetryCount = 0
                    Log.i(TAG, "Coded advertising set started (tx $txPower dBm)")
                    BleLinkDiagnostics.onCodedAdvertiser(
                        BleLinkDiagnostics.AdvertiserState.ACTIVE, txPowerDbm = txPower
                    )
                    return
                }
                Log.w(TAG, "Coded advertising set failed: $status")
                if (codedAdvertisingCallback === this) codedAdvertisingCallback = null
                val permanent = status == ADVERTISE_FAILED_FEATURE_UNSUPPORTED ||
                    status == ADVERTISE_FAILED_DATA_TOO_LARGE
                BleLinkDiagnostics.onCodedAdvertiser(
                    if (permanent) BleLinkDiagnostics.AdvertiserState.UNSUPPORTED
                    else BleLinkDiagnostics.AdvertiserState.FAILED,
                    error = status
                )
                if (!permanent && status != ADVERTISE_FAILED_ALREADY_STARTED) scheduleCodedAdvertiseRestart()
            }

            override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
                Log.d(TAG, "Coded advertising set stopped")
            }
        }

        try {
            codedAdvertisingCallback = callback
            BleLinkDiagnostics.onCodedAdvertiser(BleLinkDiagnostics.AdvertiserState.STARTING)
            advertiser.startAdvertisingSet(parameters, data, null, null, null, callback)
        } catch (e: Exception) {
            codedAdvertisingCallback = null
            Log.e(TAG, "Exception starting coded advertising set: ${e.message}")
            BleLinkDiagnostics.onCodedAdvertiser(BleLinkDiagnostics.AdvertiserState.FAILED)
        }
    }

    @SuppressLint("MissingPermission") // guarded by hasBluetoothPermissions()
    private fun stopCodedAdvertising() {
        val callback = codedAdvertisingCallback ?: return
        codedAdvertisingCallback = null
        if (!permissionManager.hasBluetoothPermissions()) return
        try {
            bleAdvertiser?.stopAdvertisingSet(callback)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping coded advertising set: ${e.message}")
        }
        BleLinkDiagnostics.onCodedAdvertiser(BleLinkDiagnostics.AdvertiserState.STOPPED)
    }

    private fun scheduleCodedAdvertiseRestart() {
        codedAdvertiseRetryCount++
        if (codedAdvertiseRetryCount > 5) {
            BleLinkDiagnostics.event("Coded advertising gave up after ${codedAdvertiseRetryCount - 1} retries")
            return
        }
        val delayMs = (ADVERTISE_RETRY_BASE_MS * codedAdvertiseRetryCount).coerceAtMost(ADVERTISE_MAX_RETRY_DELAY_MS)
        connectionScope.launch {
            delay(delayMs)
            if (isActive && isServerRoleEnabled() && codedAdvertisingCallback == null) {
                startCodedAdvertising(longRangeMode(), peerIDAdvertisementBytes())
            }
        }
    }

    /**
     * Ask for LE Coded S=8 on a server-side link when the policy wants maximum range. The remote
     * controller may refuse (no Coded support, iOS), in which case the link keeps its PHY.
     */
    @SuppressLint("MissingPermission") // guarded by hasBluetoothPermissions()
    private fun readServerPhy(device: BluetoothDevice) {
        if (!permissionManager.hasBluetoothPermissions()) return
        try { gattServer?.readPhy(device) } catch (_: Exception) { }
    }

    @SuppressLint("MissingPermission") // guarded by hasBluetoothPermissions()
    private fun requestLinkPhy(device: BluetoothDevice, linkID: String) {
        val server = gattServer ?: return
        if (!permissionManager.hasBluetoothPermissions()) return
        val mode = longRangeMode()
        try {
            if (BleLongRangePolicy.shouldRequestCodedOnConnect(mode, capabilities())) {
                val coding = codedCoding()
                BleLinkDiagnostics.onPhyRequested(linkID, "Coded ${coding.label}")
                server.setPreferredPhy(
                    device,
                    BluetoothDevice.PHY_LE_CODED_MASK,
                    BluetoothDevice.PHY_LE_CODED_MASK,
                    BleLongRangePolicy.phyOption(coding)
                )
            } else if (mode != BleLongRangeMode.OFF) {
                server.readPhy(device)
            }
        } catch (e: Exception) {
            Log.w(TAG, "PHY request failed for ${device.address}: ${e.message}")
        }
    }

    private fun startAdaptiveLoop() {
        adaptiveJob?.cancel()
        adaptiveJob = connectionScope.launch {
            while (isActive) {
                delay(ADAPTIVE_EVAL_INTERVAL_MS)
                try {
                    if (longRangeMode() == BleLongRangeMode.ADAPTIVE && capabilities().leCodedPhy) {
                        serverPhyStates.values.forEach { evaluateAdaptive(it) }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Adaptive evaluation failed: ${e.message}")
                }
            }
        }
    }

    private fun evaluateAdaptive(state: ServerPhyState) {
        val address = state.device.address
        // Our own outgoing connection reported on the server side: the client controller owns it.
        if (isOwnOutgoing(address)) return
        val peerID = connectionTracker.addressPeerMap[address]
        if (peerID == null) {
            BleLinkDiagnostics.onAdaptiveState(state.linkID, null, "waiting for peer identity")
            return
        }
        val rssi = outgoingLinkRssi(peerID)
        if (rssi == null) {
            BleLinkDiagnostics.onAdaptiveState(
                state.linkID, null, "no outgoing connection to this peer: only send failures switch it"
            )
            return
        }
        val now = System.currentTimeMillis()
        BleLinkDiagnostics.onLinkRssi(state.linkID, rssi, source = "outgoing link")
        state.adaptive.onRssi(rssi, state.isCoded, now)?.let { decision ->
            val emergency = decision.reason == AdaptivePhyController.Reason.SIGNAL_DROP
            if (decision.target == AdaptivePhyController.Target.CODED && (emergency || now >= state.remoteChose1mUntil)) {
                requestAdaptiveCoded(state, reasonText(decision.reason, rssi) + " (on our outgoing link)")
            }
        }
        BleLinkDiagnostics.onAdaptiveState(
            state.linkID,
            state.adaptive.smoothedRssi?.toInt(),
            if (state.isCoded) "incoming side: returns to 1M only if the peer asks"
            else state.adaptive.pendingNote(currentlyCoded = false, nowMs = now)
        )
    }

    /** Send results on incoming links: repeated failures move a 1M link to Coded. */
    fun onSendResult(linkID: String, success: Boolean) {
        if (longRangeMode() != BleLongRangeMode.ADAPTIVE) return
        val state = serverPhyStates[linkID] ?: return
        if (!capabilities().leCodedPhy) return
        state.adaptive.onSendResult(success, state.isCoded)?.let { decision ->
            requestAdaptiveCoded(state, reasonText(decision.reason, null))
        }
    }

    @SuppressLint("MissingPermission") // guarded by hasBluetoothPermissions()
    private fun requestAdaptiveCoded(state: ServerPhyState, reason: String) {
        val server = gattServer ?: return
        if (!permissionManager.hasBluetoothPermissions()) return
        val now = System.currentTimeMillis()
        if (state.isCoded) return
        // Spaced retries: the remote may refuse silently (no Coded support, or it holds 1M).
        if (!state.backoff.allow(AdaptivePhyController.Target.CODED, now)) return
        try {
            state.lastRequestAt = now
            val coding = codedCoding()
            BleLinkDiagnostics.event("Adaptive: server ${state.device.address} -> Coded ($reason)")
            BleLinkDiagnostics.onPhyRequested(state.linkID, "Coded ${coding.label}")
            server.setPreferredPhy(
                state.device,
                BluetoothDevice.PHY_LE_CODED_MASK,
                BluetoothDevice.PHY_LE_CODED_MASK,
                BleLongRangePolicy.phyOption(coding)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Adaptive PHY request failed for ${state.device.address}: ${e.message}")
        }
        // A refused or no-op change may produce no callback: re-read so the state is known.
        connectionScope.launch {
            delay(2_000L)
            if (serverPhyStates[state.linkID] === state) readServerPhy(state.device)
        }
    }

    /** Re-apply the PHY policy to current server links after a mode change. */
    @SuppressLint("MissingPermission") // guarded by hasBluetoothPermissions()
    fun applyLinkPhyPolicy() {
        val server = gattServer ?: return
        if (!permissionManager.hasBluetoothPermissions()) return
        val mode = longRangeMode()
        serverLinkIDs.forEach { (address, linkID) ->
            val device = connectionTracker.getDeviceConnection(address)?.device ?: return@forEach
            if (mode == BleLongRangeMode.OFF) {
                try {
                    BleLinkDiagnostics.onPhyRequested(linkID, "1M")
                    server.setPreferredPhy(
                        device,
                        BluetoothDevice.PHY_LE_1M_MASK,
                        BluetoothDevice.PHY_LE_1M_MASK,
                        BluetoothDevice.PHY_OPTION_NO_PREFERRED
                    )
                } catch (_: Exception) { }
            } else {
                requestLinkPhy(device, linkID)
            }
        }
    }
    
    /**
     * Stop advertising
     */
    @Suppress("DEPRECATION")
    private fun stopAdvertising() {
        if (!permissionManager.hasBluetoothPermissions() || bleAdvertiser == null) return
        try {
            advertiseCallback?.let { cb -> bleAdvertiser.stopAdvertising(cb) }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping advertising: ${e.message}")
        }
        BleLinkDiagnostics.onLegacyAdvertiser(BleLinkDiagnostics.AdvertiserState.STOPPED)
        stopCodedAdvertising()
    }
    
    /**
     * Schedule an advertising restart with incremental backoff after a transient failure.
     */
    private fun scheduleAdvertiseRestart(reason: String) {
        advertiseRetryCount++
        val delayMs = (ADVERTISE_RETRY_BASE_MS * advertiseRetryCount).coerceAtMost(ADVERTISE_MAX_RETRY_DELAY_MS)
        Log.w(TAG, "Scheduling advertising restart in ${delayMs}ms (attempt $advertiseRetryCount, reason=$reason)")
        connectionScope.launch {
            delay(delayMs)
            if (isActive && isServerRoleEnabled()) {
                stopAdvertising()
                delay(100)
                startAdvertising()
            }
        }
    }

    /**
     * Restart advertising (for power mode changes)
     */
    fun restartAdvertising() {
        // Respect debug setting
        val enabled = isServerRoleEnabled()
        if (!isActive || !enabled) {
            stopAdvertising()
            return
        }

        connectionScope.launch {
            stopAdvertising()
            delay(100)
            startAdvertising()
        }
    }
}
