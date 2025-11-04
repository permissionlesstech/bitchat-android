package com.bitchat.android.mesh

import android.content.Context
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pManager.ActionListener
import android.net.wifi.p2p.WifiP2pManager.Channel
import android.util.Log
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Wi-Fi Direct connection manager for local peer-to-peer communication without internet
 * Provides direct device-to-device connectivity using Wi-Fi Direct (formerly Wi-Fi P2P)
 */
class WiFiDirectConnectionManager(
    private val context: Context,
    private val myPeerID: String,
) : PowerManagerDelegate {

    companion object {
        private const val TAG = "WiFiDirectConnectionManager"
    }

    // Wi-Fi P2P Manager and Channel
    private val wifiP2pManager: WifiP2pManager? = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var wifiP2pChannel: Channel? = null

    // Power management (reuse existing PowerManager)
    private val powerManager = PowerManager(context.applicationContext)

    // Coroutines
    private val connectionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Component managers
    private val permissionManager = WiFiDirectPermissionManager(context)
    private val packetBroadcaster = WiFiDirectPacketBroadcaster(connectionScope).apply {
        packetDelegate = object : WiFiDirectPacketDelegate {
            override fun onPacketReceived(packet: BitchatPacket, deviceAddress: String) {
                // Find the peer ID for this device address
                val peerID = devicePeerMap[deviceAddress] ?: "wifi_peer_$deviceAddress"

                // Forward to the connection manager delegate
                delegate?.onPacketReceived(packet, peerID, connectedDevices[deviceAddress])
            }
        }
    }

    // Connection state tracking
    private val connectedDevices = mutableMapOf<String, WifiP2pDevice>() // deviceAddress -> device
    private val devicePeerMap = mutableMapOf<String, String>() // deviceAddress -> peerID

    // Service state
    private var isActive = false

    // Delegate for callbacks
    var delegate: WiFiDirectConnectionManagerDelegate? = null

    // Wi-Fi P2P broadcast receiver
    private var wifiP2pReceiver: WiFiDirectBroadcastReceiver? = null

    init {
        powerManager.delegate = this

        // Observe debug settings
        try {
            val dbg = com.bitchat.android.ui.debug.DebugSettingsManager.getInstance()
            connectionScope.launch {
                dbg.wifiDirectEnabled.collect { enabled ->
                    if (!isActive) return@collect
                    if (enabled) startDiscovery() else stopDiscovery()
                }
            }
        } catch (_: Exception) { }
    }

    /**
     * Start Wi-Fi Direct services
     */
    fun startServices(): Boolean {
        if (isActive) {
            Log.w(TAG, "Wi-Fi Direct services already active")
            return true
        }

        if (wifiP2pManager == null) {
            Log.e(TAG, "Wi-Fi P2P not supported on this device")
            return false
        }

        Log.i(TAG, "Starting Wi-Fi Direct services with peer ID: $myPeerID")

        // Check permissions
        if (!permissionManager.hasRequiredPermissions()) {
            Log.e(TAG, "Missing required Wi-Fi permissions")
            return false
        }

        // Initialize Wi-Fi P2P channel if not already done
        if (wifiP2pChannel == null) {
            wifiP2pChannel = wifiP2pManager.initialize(context, context.mainLooper, object : WifiP2pManager.ChannelListener {
                override fun onChannelDisconnected() {
                    Log.w(TAG, "Wi-Fi P2P channel disconnected")
                    if (isActive) {
                        // Try to reinitialize
                        wifiP2pChannel = wifiP2pManager?.initialize(context, context.mainLooper, this)
                    }
                }
            })
        }

        if (wifiP2pChannel == null) {
            Log.e(TAG, "Failed to initialize Wi-Fi P2P channel")
            return false
        }

        // Register broadcast receiver
        wifiP2pReceiver = WiFiDirectBroadcastReceiver(wifiP2pManager, wifiP2pChannel!!, this)
        wifiP2pReceiver?.register(context)

        isActive = true
        powerManager.start()

        // Start discovery
        startDiscovery()

        return true
    }

    /**
     * Stop Wi-Fi Direct services
     */
    fun stopServices() {
        if (!isActive) {
            Log.w(TAG, "Wi-Fi Direct services not active")
            return
        }

        Log.i(TAG, "Stopping Wi-Fi Direct services")
        isActive = false

        // Stop discovery
        stopDiscovery()

        // Disconnect all peers
        disconnectAllPeers()

        // Unregister broadcast receiver
        wifiP2pReceiver?.unregister(context)
        wifiP2pReceiver = null

        // Cancel all transfers
        packetBroadcaster.cancelAllTransfers()

        // Clear state
        connectedDevices.clear()
        devicePeerMap.clear()

        powerManager.stop()
    }

    /**
     * Start peer discovery
     */
    private fun startDiscovery() {
        if (!isActive || wifiP2pManager == null || wifiP2pChannel == null) return

        wifiP2pManager.discoverPeers(wifiP2pChannel!!, object : ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Peer discovery started successfully")
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "Failed to start peer discovery: $reason")
            }
        })
    }

    /**
     * Stop peer discovery
     */
    private fun stopDiscovery() {
        if (wifiP2pManager == null || wifiP2pChannel == null) return

        wifiP2pManager.stopPeerDiscovery(wifiP2pChannel!!, object : ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Peer discovery stopped successfully")
            }

            override fun onFailure(reason: Int) {
                Log.w(TAG, "Failed to stop peer discovery: $reason")
            }
        })
    }

    /**
     * Connect to a Wi-Fi P2P device
     */
    fun connectToDevice(device: WifiP2pDevice): Boolean {
        if (!isActive || wifiP2pManager == null || wifiP2pChannel == null) return false

        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
        }

        wifiP2pManager.connect(wifiP2pChannel!!, config, object : ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Connection request sent to ${device.deviceAddress}")
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "Failed to connect to ${device.deviceAddress}: $reason")
            }
        })

        return true
    }

    /**
     * Disconnect all peers
     */
    private fun disconnectAllPeers() {
        if (wifiP2pManager == null || wifiP2pChannel == null) return

        wifiP2pManager.removeGroup(wifiP2pChannel!!, object : ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Disconnected all Wi-Fi P2P peers")
            }

            override fun onFailure(reason: Int) {
                Log.w(TAG, "Failed to disconnect peers: $reason")
            }
        })
    }

    /**
     * Broadcast a packet to all connected peers
     */
    fun broadcastPacket(routed: RoutedPacket) {
        if (!isActive) return

        packetBroadcaster.broadcastPacket(routed, connectedDevices.values.toList())
    }

    /**
     * Send packet to specific peer
     */
    fun sendPacketToPeer(peerID: String, packet: BitchatPacket): Boolean {
        if (!isActive) return false

        val deviceAddress = devicePeerMap.entries.find { it.value == peerID }?.key
        val device = deviceAddress?.let { connectedDevices[it] }

        if (device != null) {
            packetBroadcaster.sendPacketToDevice(packet, device)
            return true
        }

        return false
    }

    /**
     * Cancel file transfer
     */
    fun cancelTransfer(transferId: String): Boolean {
        return packetBroadcaster.cancelTransfer(transferId)
    }

    /**
     * Handle Wi-Fi P2P state change
     */
    internal fun onWifiP2pStateChanged(enabled: Boolean) {
        Log.d(TAG, "Wi-Fi P2P state changed: ${if (enabled) "ENABLED" else "DISABLED"}")

        if (!enabled && isActive) {
            Log.w(TAG, "Wi-Fi P2P disabled while service is active")
            // Service will continue but won't function until Wi-Fi P2P is re-enabled
        }
    }

    /**
     * Handle peers discovered
     */
    internal fun onPeersAvailable(peers: WifiP2pDeviceList) {
        if (!isActive) return

        Log.d(TAG, "Found ${peers.deviceList.size} Wi-Fi P2P peers")
        for (device in peers.deviceList) {
            Log.d(TAG, "Peer: ${device.deviceName} (${device.deviceAddress}), status: ${device.status}")
        }

        // Auto-connect to discovered peers if configured
        // For now, we'll let the mesh service decide when to connect
    }

    /**
     * Handle connection info available
     */
    internal fun onConnectionInfoAvailable(info: WifiP2pInfo) {
        Log.d(TAG, "Connection info available: groupFormed=${info.groupFormed}, isGroupOwner=${info.isGroupOwner}")

        if (info.groupFormed) {
            // We have a connection, start data transfer
            packetBroadcaster.onConnectionEstablished(info)
        }
    }

    /**
     * Handle this device changed
     */
    internal fun onThisDeviceChanged(device: WifiP2pDevice) {
        Log.d(TAG, "This device changed: ${device.deviceName} (${device.deviceAddress})")
        // Could update device info if needed
    }

    /**
     * Handle device connected
     */
    internal fun onDeviceConnected(device: WifiP2pDevice) {
        Log.d(TAG, "Device connected: ${device.deviceName} (${device.deviceAddress})")
        connectedDevices[device.deviceAddress] = device
        delegate?.onDeviceConnected(device)
    }

    /**
     * Handle device disconnected
     */
    internal fun onDeviceDisconnected(device: WifiP2pDevice) {
        Log.d(TAG, "Device disconnected: ${device.deviceName} (${device.deviceAddress})")
        connectedDevices.remove(device.deviceAddress)
        devicePeerMap.remove(device.deviceAddress)
        delegate?.onDeviceDisconnected(device)
    }

    /**
     * Map device address to peer ID
     */
    fun mapDeviceToPeer(deviceAddress: String, peerID: String) {
        devicePeerMap[deviceAddress] = peerID
        Log.d(TAG, "Mapped device $deviceAddress to peer $peerID")
    }

    /**
     * Get connected devices
     */
    fun getConnectedDevices(): List<WifiP2pDevice> = connectedDevices.values.toList()

    /**
     * Get device address for peer ID
     */
    fun getDeviceAddressForPeer(peerID: String): String? {
        return devicePeerMap.entries.find { it.value == peerID }?.key
    }

    /**
     * Check if device is supported
     */
    fun isSupported(): Boolean {
        return wifiP2pManager != null
    }

    /**
     * Set nickname resolver for logging
     */
    fun setNicknameResolver(resolver: (String) -> String?) {
        packetBroadcaster.setNicknameResolver(resolver)
    }

    // PowerManagerDelegate implementation
    override fun onPowerModeChanged(newMode: PowerManager.PowerMode) {
        Log.i(TAG, "Power mode changed to: $newMode")

        connectionScope.launch {
            when (newMode) {
                PowerManager.PowerMode.PERFORMANCE -> {
                    // Full power - enable all features
                    if (isActive) {
                        startDiscovery()
                    }
                }
                PowerManager.PowerMode.BALANCED -> {
                    // Moderate power saving - normal operation
                    if (isActive) {
                        startDiscovery()
                    }
                }
                PowerManager.PowerMode.POWER_SAVER -> {
                    // Aggressive power saving - reduce discovery
                    if (isActive) {
                        stopDiscovery()
                    }
                }
                PowerManager.PowerMode.ULTRA_LOW_POWER -> {
                    // Minimal operations - stop discovery
                    stopDiscovery()
                }
            }
        }
    }

    override fun onScanStateChanged(shouldScan: Boolean) {
        if (shouldScan && isActive) {
            startDiscovery()
        } else {
            stopDiscovery()
        }
    }
}

/**
 * Delegate interface for Wi-Fi Direct connection manager callbacks
 */
interface WiFiDirectConnectionManagerDelegate {
    fun onPacketReceived(packet: BitchatPacket, peerID: String, device: WifiP2pDevice?)
    fun onDeviceConnected(device: WifiP2pDevice)
    fun onDeviceDisconnected(device: WifiP2pDevice)
}
