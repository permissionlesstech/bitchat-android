package com.bitchat.android.mesh

import android.bluetooth.*
import android.content.Context
import android.util.Log
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/**
 * Power-optimized Bluetooth connection manager with comprehensive memory management
 * Integrates with PowerManager for adaptive power consumption
 * Coordinates smaller, focused components for better maintainability
 */
class BluetoothConnectionManager(
    private val context: Context, 
    private val myPeerID: String,
    private val fragmentManager: FragmentManager? = null
) : PowerManagerDelegate {
    
    companion object {
        private const val TAG = "BluetoothConnectionManager"
    }
    
    // Core Bluetooth components
    private val bluetoothManager: BluetoothManager = 
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    
    // Power management
    private val powerManager = PowerManager(context.applicationContext)
    
    // Coroutines
    private val connectionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Component managers
    private val permissionManager = BluetoothPermissionManager(context)
    private val connectionTracker = BluetoothConnectionTracker(connectionScope, powerManager)
    private val packetBroadcaster = BluetoothPacketBroadcaster(connectionScope, connectionTracker, fragmentManager)
    
    // Meshtastic Fragmentation Manager (Lower MTU for LoRa)
    // Uses smaller fragment sizes (~230 bytes) to fit in Meshtastic PRIVATE_APP payload
    private val meshtasticFragmentManager = FragmentManager(
        fragmentSizeThreshold = com.bitchat.android.util.AppConstants.Mesh.Meshtastic.MAX_PAYLOAD_SIZE,
        maxFragmentSize = 200 // Conservative chunk size for Meshtastic
    )
    
    // Meshtastic manager
    private val meshtasticManager = MeshtasticConnectionManager(context, connectionScope) { packetBytes ->
        // Handle incoming packets from Meshtastic
        // Currently assuming they are raw Bitchat packets (or wrapped BitchatPacket objects serialized)
        // For now, let's treat them as raw bytes and try to parse them if possible or pass them up 
        // Note: The original architecture expects BitchatPacket object. We'll need to parse.
        
        // Check if this is a fragment packet
        val rawPacket = BitchatPacket.fromBinaryData(packetBytes)
        if (rawPacket?.type == com.bitchat.android.protocol.MessageType.FRAGMENT.value) {
             // Reassembly logic
             val reassembled = meshtasticFragmentManager.handleFragment(rawPacket)
             if (reassembled != null) {
                 componentDelegate.onPacketReceived(reassembled, BitchatPacket.byteArrayToHexString(reassembled.senderID), null)
             }
             return@MeshtasticConnectionManager
        }
        
        BitchatPacket.fromBinaryData(packetBytes)?.let { packet ->
            Log.d(TAG, "Parsed packet from Meshtastic: ${packet.type}")
            // Inject into the receive pipeline
             // We treat the Meshtastic device as "unknown" BLE device or just use a placeholder
            componentDelegate.onPacketReceived(packet, BitchatPacket.byteArrayToHexString(packet.senderID), null)
        }
    }
    
    // Delegate for component managers to call back to main manager
    private val componentDelegate = object : BluetoothConnectionManagerDelegate {
        override fun onPacketReceived(packet: BitchatPacket, peerID: String, device: BluetoothDevice?) {
            Log.d(TAG, "onPacketReceived: Packet received from ${device?.address} ($peerID)")
            device?.let { bluetoothDevice ->
                // Get current RSSI for this device and update if available
                val currentRSSI = connectionTracker.getBestRSSI(bluetoothDevice.address)
                if (currentRSSI != null) {
                    delegate?.onRSSIUpdated(bluetoothDevice.address, currentRSSI)
                }
            }

            if (peerID == myPeerID) return // Ignore messages from self

            delegate?.onPacketReceived(packet, peerID, device)
        }
        
        override fun onDeviceConnected(device: BluetoothDevice) {
            delegate?.onDeviceConnected(device)
        }

        override fun onDeviceDisconnected(device: BluetoothDevice) {
            delegate?.onDeviceDisconnected(device)
        }
        
        override fun onRSSIUpdated(deviceAddress: String, rssi: Int) {
            delegate?.onRSSIUpdated(deviceAddress, rssi)
        }
    }
    
    private val serverManager = BluetoothGattServerManager(
        context, connectionScope, connectionTracker, permissionManager, powerManager, componentDelegate
    )
    private val clientManager = BluetoothGattClientManager(
        context, connectionScope, connectionTracker, permissionManager, powerManager, componentDelegate
    )
    
    // Service state
    private var isActive = false
    
    // Delegate for callbacks
    var delegate: BluetoothConnectionManagerDelegate? = null
    
    // Public property for address-peer mapping
    val addressPeerMap get() = connectionTracker.addressPeerMap

    // Expose first-announce helpers to higher layers
    fun noteAnnounceReceived(address: String) { connectionTracker.noteAnnounceReceived(address) }
    fun hasSeenFirstAnnounce(address: String): Boolean = connectionTracker.hasSeenFirstAnnounce(address)
    
    init {
        powerManager.delegate = this
        // Observe debug settings to enforce role state while active
        try {
            val dbg = com.bitchat.android.ui.debug.DebugSettingsManager.getInstance()
            // Role enable/disable
            connectionScope.launch {
                dbg.gattServerEnabled.collect { enabled ->
                    if (!isActive) return@collect
                    if (enabled) startServer() else stopServer()
                }
            }
            connectionScope.launch {
                dbg.gattClientEnabled.collect { enabled ->
                    if (!isActive) return@collect
                    if (enabled) startClient() else stopClient()
                }
            }
            // Connection caps: enforce on change
            connectionScope.launch {
                dbg.maxConnectionsOverall.collect {
                    if (!isActive) return@collect
                    connectionTracker.enforceConnectionLimits()
                    // Also enforce server side best-effort
                    serverManager.enforceServerLimit(dbg.maxServerConnections.value)
                }
            }
            connectionScope.launch {
                dbg.maxClientConnections.collect {
                    if (!isActive) return@collect
                    connectionTracker.enforceConnectionLimits()
                }
            }
            connectionScope.launch {
                dbg.maxServerConnections.collect {
                    if (!isActive) return@collect
                    serverManager.enforceServerLimit(dbg.maxServerConnections.value)
                }
            }
        } catch (_: Exception) { }
    }
    
    /**
     * Start all Bluetooth services with power optimization
     */
    fun startServices(): Boolean {
        Log.i(TAG, "Starting power-optimized Bluetooth services...")
        
        if (!permissionManager.hasBluetoothPermissions()) {
            Log.e(TAG, "Missing Bluetooth permissions")
            return false
        }
        
        if (bluetoothAdapter?.isEnabled != true) {
            Log.e(TAG, "Bluetooth is not enabled")
            return false
        }
        
        try {
            isActive = true
            Log.d(TAG, "ConnectionManager activated (permissions and adapter OK)")

        // set the adapter's name to our 8-character peerID for iOS privacy, TODO: Make this configurable
        // try {
        //     if (bluetoothAdapter?.name != myPeerID) {
        //         bluetoothAdapter?.name = myPeerID
        //         Log.d(TAG, "Set Bluetooth adapter name to peerID: $myPeerID for iOS compatibility.")
        //     }
        // } catch (se: SecurityException) {
        //     Log.e(TAG, "Missing BLUETOOTH_CONNECT permission to set adapter name.", se)
        // }

            // Start all component managers
            connectionScope.launch {
                // Start connection tracker first
                connectionTracker.start()
                
                // Start power manager
                powerManager.start()
                
                // Start server/client based on debug settings
                val dbg = try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance() } catch (_: Exception) { null }
                val startServer = dbg?.gattServerEnabled?.value != false
                val startClient = dbg?.gattClientEnabled?.value != false

                if (startServer) {
                    if (!serverManager.start()) {
                        Log.e(TAG, "Failed to start server manager")
                        this@BluetoothConnectionManager.isActive = false
                        return@launch
                    }
                    Log.d(TAG, "GATT Server started")
                } else {
                    Log.i(TAG, "GATT Server disabled by debug settings; not starting")
                }

                if (startClient) {
                    if (!clientManager.start()) {
                        Log.e(TAG, "Failed to start client manager")
                        this@BluetoothConnectionManager.isActive = false
                        return@launch
                    }
                    Log.d(TAG, "GATT Client started")
                } else {
                    Log.i(TAG, "GATT Client disabled by debug settings; not starting")
                }
                
                // Disconnect any lingering Meshtastic connections to start fresh
                meshtasticManager.disconnect()
                
                Log.i(TAG, "Bluetooth services started successfully")
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
            Log.d(TAG, "Stopping client/server and power components...")
            // Stop component managers
            clientManager.stop()
            serverManager.stop()
            
            // Stop power manager
            powerManager.stop()
            
            // Stop connection tracker
            connectionTracker.stop()
            
            meshtasticManager.disconnect()
            
            // Cancel the coroutine scope
            connectionScope.cancel()
            
            Log.i(TAG, "All Bluetooth services stopped")
        }
    }

    /**
     * Indicates whether this instance can be safely reused for a future start.
     * Returns false if its coroutine scope has been cancelled.
     */
    fun isReusable(): Boolean {
        val active = connectionScope.isActive
        if (!active) {
            Log.d(TAG, "BluetoothConnectionManager isReusable=false (scope cancelled)")
        }
        return active
    }
    
    /**
     * Set app background state for power optimization
     */
    fun setAppBackgroundState(inBackground: Boolean) {
        powerManager.setAppBackgroundState(inBackground)
    }

    /**
     * Broadcast packet to connected devices with connection limit enforcement
     * Automatically fragments large packets to fit within BLE MTU limits
     */
    fun broadcastPacket(routed: RoutedPacket) {
        if (!isActive) return
        
        packetBroadcaster.broadcastPacket(
            routed,
            serverManager.getGattServer(),
            serverManager.getCharacteristic()
        )
        
        // Also broadcast to Meshtastic if connected
        // We need to serialize the packet to bytes.
        // Currently RoutedPacket wraps BitchatPacket. 
        // Ideally we grab the raw bytes if available, or we need a serializer.
        // Since BitchatPacket usually has a toByteArray() or equivalent in the Protocol layer (not visible here directly easily without heavy imports),
        // we will check if routed.packet has a serialization method.
        
        try {
            val rawBytes = routed.packet.toBinaryData()
            if (rawBytes != null) {
                // Check size for LoRa MTU
                if (rawBytes.size > com.bitchat.android.util.AppConstants.Mesh.Meshtastic.MAX_PAYLOAD_SIZE) {
                    // Use the configured meshtasticFragmentManager to split the packet
                    // This ensures consistent logic with standard BLE fragmentation but with LoRa-specific MTU
                    val loRaFragments = meshtasticFragmentManager.createFragments(routed.packet)
                    
                    loRaFragments.forEach { frag ->
                        frag.toBinaryData()?.let { fragBytes ->
                             meshtasticManager.sendPacket(fragBytes)
                             // Small delay to prevent LoRa radio TX queue overflow
                             Thread.sleep(250) 
                        }
                    }
                    
                } else {
                    // Send directly
                    meshtasticManager.sendPacket(rawBytes)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error serializing packet for Meshtastic broadcast", e)
        }
    }

    fun cancelTransfer(transferId: String): Boolean {
        return packetBroadcaster.cancelTransfer(transferId)
    }

    /**
     * Send a packet directly to a specific peer, without broadcasting to others.
     */
    fun sendPacketToPeer(peerID: String, packet: BitchatPacket): Boolean {
        if (!isActive) return false
        return packetBroadcaster.sendPacketToPeer(
            RoutedPacket(packet),
            peerID,
            serverManager.getGattServer(),
            serverManager.getCharacteristic()
        )
    }
    

    // Expose role controls for debug UI
    fun startServer() { connectionScope.launch { serverManager.start() } }
    fun stopServer() { connectionScope.launch { serverManager.stop() } }
    fun startClient() { connectionScope.launch { clientManager.start() } }
    fun stopClient() { connectionScope.launch { clientManager.stop() } }

    // Inject nickname resolver for broadcaster logs
    fun setNicknameResolver(resolver: (String) -> String?) { packetBroadcaster.setNicknameResolver(resolver) }

    // Debug snapshots for connected devices
    fun getConnectedDeviceEntries(): List<Triple<String, Boolean, Int?>> {
        return try {
            connectionTracker.getConnectedDevices().values.map { dc ->
                val rssi = if (dc.rssi != Int.MIN_VALUE) dc.rssi else null
                Triple(dc.device.address, dc.isClient, rssi)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Expose local adapter address for debug UI
    fun getLocalAdapterAddress(): String? = try { bluetoothAdapter?.address } catch (e: Exception) { null }

    fun isClientConnection(address: String): Boolean? {
        return try { connectionTracker.getConnectedDevices()[address]?.isClient } catch (e: Exception) { null }
    }

    /**
     * Public: connect/disconnect helpers for debug UI
     */
    fun connectToAddress(address: String): Boolean = clientManager.connectToAddress(address)
    fun disconnectAddress(address: String) { connectionTracker.disconnectDevice(address) }


    // Optionally disconnect all connections (server and client)
    fun disconnectAll() {
        connectionScope.launch {
            // Stop and restart to force disconnects
            clientManager.stop()
            serverManager.stop()
            delay(200)
            if (isActive) {
                // Restart managers if service is active
                serverManager.start()
                clientManager.start()
            }
        }
    }
    
    /**
     * Connect to a specific Meshtastic device
     */
    fun connectMeshtasticDevice(device: BluetoothDevice) {
        meshtasticManager.connect(device)
    }

    
    /**
     * Get connected device count
     */
    fun getConnectedDeviceCount(): Int = connectionTracker.getConnectedDeviceCount()
    
    /**
     * Get debug information including power management
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Bluetooth Connection Manager ===")
            appendLine("Bluetooth MAC Address: ${bluetoothAdapter?.address}")
            appendLine("Active: $isActive")
            appendLine("Bluetooth Enabled: ${bluetoothAdapter?.isEnabled}")
            appendLine("Has Permissions: ${permissionManager.hasBluetoothPermissions()}")
            appendLine("GATT Server Active: ${serverManager.getGattServer() != null}")
            appendLine()
            appendLine(powerManager.getPowerInfo())
            appendLine()
            appendLine(connectionTracker.getDebugInfo())
        }
    }
    
    // MARK: - PowerManagerDelegate Implementation
    
    override fun onPowerModeChanged(newMode: PowerManager.PowerMode) {
        Log.i(TAG, "Power mode changed to: $newMode")
        
        connectionScope.launch {
            // Avoid rapid scan restarts by checking if we need to change scan behavior
            val wasUsingDutyCycle = powerManager.shouldUseDutyCycle()
            
            // Update advertising with new power settings if server enabled
            val serverEnabled = try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().gattServerEnabled.value } catch (_: Exception) { true }
            if (serverEnabled) {
                serverManager.restartAdvertising()
            } else {
                serverManager.stop()
            }
            
            // Only restart scanning if the duty cycle behavior changed
            val nowUsingDutyCycle = powerManager.shouldUseDutyCycle()
            if (wasUsingDutyCycle != nowUsingDutyCycle) {
                Log.d(TAG, "Duty cycle behavior changed (${wasUsingDutyCycle} -> ${nowUsingDutyCycle}), restarting scan")
                val clientEnabled = try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().gattClientEnabled.value } catch (_: Exception) { true }
                if (clientEnabled) {
                    clientManager.restartScanning()
                } else {
                    clientManager.stop()
                }
            } else {
                Log.d(TAG, "Duty cycle behavior unchanged, keeping existing scan state")
            }
            
            // Enforce connection limits
            connectionTracker.enforceConnectionLimits()
            // Best-effort server cap
            try {
                val maxServer = com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().maxServerConnections.value
                serverManager.enforceServerLimit(maxServer)
            } catch (_: Exception) { }
        }
    }
    
    override fun onScanStateChanged(shouldScan: Boolean) {
        clientManager.onScanStateChanged(shouldScan)
    }
    
    // MARK: - Private Implementation - All moved to component managers
}

/**
 * Delegate interface for Bluetooth connection manager callbacks
 */
interface BluetoothConnectionManagerDelegate {
    fun onPacketReceived(packet: BitchatPacket, peerID: String, device: BluetoothDevice?)
    fun onDeviceConnected(device: BluetoothDevice)
    fun onDeviceDisconnected(device: BluetoothDevice)
    fun onRSSIUpdated(deviceAddress: String, rssi: Int)
}
