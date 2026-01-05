package com.bitchat.android.mesh

import android.content.Context
import android.util.Log
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.sync.GossipSyncManager
import com.bitchat.android.util.toHexString
import com.bitchat.android.service.TransportBridgeService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Bluetooth mesh service - REFACTORED to use component-based architecture
 * 100% compatible with iOS version and maintains exact same UUIDs, packet format, and protocol logic
 * 
 * This is now a coordinator that orchestrates the following components:
 * - PeerManager: Peer lifecycle management
 * - FragmentManager: Message fragmentation and reassembly  
 * - SecurityManager: Security, duplicate detection, encryption
 * - StoreForwardManager: Offline message caching
 * - MessageHandler: Message type processing and relay logic
 * - BluetoothConnectionManager: BLE connections and GATT operations
 * - PacketProcessor: Incoming packet routing
 */
class BluetoothMeshService(private val context: Context) : MeshService, TransportBridgeService.TransportLayer {
    companion object {
        private const val TAG = "BluetoothMeshService"
        private val MAX_TTL: UByte = com.bitchat.android.util.AppConstants.MESSAGE_TTL_HOPS
    }
    
    // Core components
    private val encryptionService = EncryptionService(context)

    // My peer identification - derived from persisted Noise identity fingerprint (first 16 hex chars)
    override val myPeerID: String = encryptionService.getIdentityFingerprint().take(16)
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val bleTransport = BleMeshTransport()
    private lateinit var meshCore: MeshCore
    internal lateinit var connectionManager: BluetoothConnectionManager
    // Service-level notification manager for background (no-UI) DMs
    private val serviceNotificationManager = com.bitchat.android.ui.NotificationManager(
        context.applicationContext,
        androidx.core.app.NotificationManagerCompat.from(context.applicationContext),
        com.bitchat.android.util.NotificationIntervalManager()
    )
    
    // Service state management
    private var isActive = false
    
    // Delegate for message callbacks (maintains same interface)
    override var delegate: BluetoothMeshDelegate? = null
        set(value) {
            field = value
            if (::meshCore.isInitialized) {
                meshCore.delegate = value
                meshCore.refreshPeerList()
            }
        }
    // Tracks whether this instance has been terminated via stopServices()
    private var terminated = false
    
    init {
        Log.i(TAG, "Initializing BluetoothMeshService for peer=$myPeerID")
        meshCore = MeshCore(
            context = context.applicationContext,
            scope = serviceScope,
            transport = bleTransport,
            encryptionService = encryptionService,
            myPeerID = myPeerID,
            maxTtl = MAX_TTL,
            sharedGossipManager = null,
            gossipConfigProvider = object : GossipSyncManager.ConfigProvider {
                override fun seenCapacity(): Int = try {
                    com.bitchat.android.ui.debug.DebugPreferenceManager.getSeenPacketCapacity(500)
                } catch (_: Exception) { 500 }

                override fun gcsMaxBytes(): Int = try {
                    com.bitchat.android.ui.debug.DebugPreferenceManager.getGcsMaxFilterBytes(400)
                } catch (_: Exception) { 400 }

                override fun gcsTargetFpr(): Double = try {
                    com.bitchat.android.ui.debug.DebugPreferenceManager.getGcsFprPercent(1.0) / 100.0
                } catch (_: Exception) { 0.01 }
            },
            hooks = MeshCore.Hooks(
                onMessageReceived = { message -> handleMessageReceived(message) },
                onPeerIdBindingUpdated = { newPeerID, _, publicKey, previousPeerID ->
                    try {
                        com.bitchat.android.favorites.FavoritesPersistenceService.shared
                            .findNostrPubkey(publicKey)
                            ?.let { npub ->
                                com.bitchat.android.favorites.FavoritesPersistenceService.shared
                                    .updateNostrPublicKeyForPeerID(newPeerID, npub)
                            }
                    } catch (_: Exception) { }
                    Log.d(TAG, "Updated peer ID binding: $newPeerID (was: $previousPeerID) publicKey=${publicKey.toHexString().take(16)}...")
                },
                onAnnounceProcessed = { routed, _ ->
                    val deviceAddress = routed.relayAddress
                    val pid = routed.peerID
                    if (deviceAddress != null && pid != null) {
                        if (!connectionManager.hasSeenFirstAnnounce(deviceAddress)) {
                            connectionManager.addressPeerMap[deviceAddress] = pid
                            connectionManager.noteAnnounceReceived(deviceAddress)
                            Log.d(TAG, "Mapped device $deviceAddress to peer $pid on FIRST-ANNOUNCE for this connection")
                            try { meshCore.setDirectConnection(pid, true) } catch (_: Exception) { }
                            try { meshCore.gossipSyncManager.scheduleInitialSyncToPeer(pid, 1_000) } catch (_: Exception) { }
                        }
                    }
                },
                readReceiptInterceptor = { messageId, recipientPeerId ->
                    val geo = runCatching { com.bitchat.android.services.MessageRouter.tryGetInstance() }.getOrNull()
                    val isGeoAlias = try {
                        val map = com.bitchat.android.nostr.GeohashAliasRegistry.snapshot()
                        map.containsKey(recipientPeerId)
                    } catch (_: Exception) { false }
                    if (isGeoAlias && geo != null) {
                        geo.sendReadReceipt(com.bitchat.android.model.ReadReceipt(messageId), recipientPeerId)
                        true
                    } else {
                        val seenStore = try {
                            com.bitchat.android.services.SeenMessageStore.getInstance(context.applicationContext)
                        } catch (_: Exception) { null }
                        if (seenStore?.hasRead(messageId) == true) {
                            Log.d(TAG, "Skipping read receipt for $messageId - already marked read")
                            true
                        } else {
                            false
                        }
                    }
                },
                onReadReceiptSent = { messageId ->
                    try {
                        com.bitchat.android.services.SeenMessageStore.getInstance(context.applicationContext)
                            .markRead(messageId)
                    } catch (_: Exception) { }
                },
                announcementNicknameProvider = {
                    try { com.bitchat.android.services.NicknameProvider.getNickname(context, myPeerID) } catch (_: Exception) { null }
                }
            )
        )

        connectionManager = BluetoothConnectionManager(context, myPeerID, meshCore.fragmentManager)
        bleTransport.connectionManager = connectionManager
        setupConnectionManagerDelegate()

        // Register as shared instance for Wi-Fi Aware transport
        com.bitchat.android.service.MeshServiceHolder.setGossipManager(meshCore.gossipSyncManager)

        Log.d(TAG, "MeshCore initialized")
        //startPeriodicDebugLogging()

        // Register with cross-layer transport bridge
        TransportBridgeService.register("BLE", this)
    }

    // TransportLayer implementation
    override fun send(packet: RoutedPacket) {
        // Received from bridge (e.g. Wi-Fi) -> Send via BLE
        // Direct injection prevents routing loops (bridge handles source check)
        meshCore.sendFromBridge(packet)
    }

    override fun sendToPeer(peerID: String, packet: BitchatPacket) {
        connectionManager.sendPacketToPeer(peerID, packet)
    }
    
    /**
     * Start periodic debug logging every 10 seconds
     */
    private fun startPeriodicDebugLogging() {
        serviceScope.launch {
            Log.d(TAG, "Starting periodic debug logging loop")
            while (isActive) {
                try {
                    delay(10000) // 10 seconds
                    if (isActive) { // Double-check before logging
                        val debugInfo = getDebugStatus()
                        Log.d(TAG, "=== PERIODIC DEBUG STATUS ===\n$debugInfo\n=== END DEBUG STATUS ===")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in periodic debug logging: ${e.message}")
                }
            }
            Log.d(TAG, "Periodic debug logging loop ended (isActive=$isActive)")
        }
    }

    private fun setupConnectionManagerDelegate() {
        try {
            connectionManager.setNicknameResolver { pid -> meshCore.getPeerNickname(pid) }
        } catch (_: Exception) { }

        connectionManager.delegate = object : BluetoothConnectionManagerDelegate {
            override fun onPacketReceived(packet: BitchatPacket, peerID: String, device: android.bluetooth.BluetoothDevice?) {
                try {
                    val nick = meshCore.getPeerNicknames()[peerID]
                    com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().logIncoming(
                        packetType = packet.type.toString(),
                        fromPeerID = peerID,
                        fromNickname = nick,
                        fromDeviceAddress = device?.address
                    )
                } catch (_: Exception) { }
                meshCore.processIncoming(packet, peerID, device?.address)
            }

            override fun onDeviceConnected(device: android.bluetooth.BluetoothDevice) {
                serviceScope.launch {
                    Log.d(TAG, "Device connected: ${device.address}; scheduling announce")
                    delay(200)
                    meshCore.sendBroadcastAnnounce()
                }
                try {
                    val addr = device.address
                    val peer = connectionManager.addressPeerMap[addr]
                    val nick = peer?.let { meshCore.getPeerNickname(it) } ?: "unknown"
                    com.bitchat.android.ui.debug.DebugSettingsManager.getInstance()
                        .logPeerConnection(peer ?: "unknown", nick, addr, isInbound = !connectionManager.isClientConnection(addr)!!)
                } catch (_: Exception) { }
            }

            override fun onDeviceDisconnected(device: android.bluetooth.BluetoothDevice) {
                Log.d(TAG, "Device disconnected: ${device.address}")
                val addr = device.address
                val peer = connectionManager.addressPeerMap[addr]
                connectionManager.addressPeerMap.remove(addr)
                if (peer != null) {
                    val stillMapped = connectionManager.addressPeerMap.values.any { it == peer }
                    if (!stillMapped) {
                        try { meshCore.setDirectConnection(peer, false) } catch (_: Exception) { }
                    }
                    try {
                        val nick = meshCore.getPeerNickname(peer) ?: "unknown"
                        com.bitchat.android.ui.debug.DebugSettingsManager.getInstance()
                            .logPeerDisconnection(peer, nick, addr)
                    } catch (_: Exception) { }
                }
            }

            override fun onRSSIUpdated(deviceAddress: String, rssi: Int) {
                connectionManager.addressPeerMap[deviceAddress]?.let { peerID ->
                    meshCore.updatePeerRSSI(peerID, rssi)
                }
            }
        }
    }

    private fun handleMessageReceived(message: BitchatMessage) {
        try {
            when {
                message.isPrivate -> {
                    val peer = message.senderPeerID ?: ""
                    if (peer.isNotEmpty()) com.bitchat.android.services.AppStateStore.addPrivateMessage(peer, message)
                }
                message.channel != null -> {
                    com.bitchat.android.services.AppStateStore.addChannelMessage(message.channel!!, message)
                }
                else -> {
                    com.bitchat.android.services.AppStateStore.addPublicMessage(message)
                }
            }
        } catch (_: Exception) { }

        if (delegate == null && message.isPrivate) {
            try {
                val senderPeerID = message.senderPeerID
                if (senderPeerID != null) {
                    val nick = try { meshCore.getPeerNickname(senderPeerID) } catch (_: Exception) { null } ?: senderPeerID
                    val preview = com.bitchat.android.ui.NotificationTextUtils.buildPrivateMessagePreview(message)
                    serviceNotificationManager.setAppBackgroundState(true)
                    serviceNotificationManager.showPrivateMessageNotification(senderPeerID, nick, preview)
                }
            } catch (_: Exception) { }
        }
    }
    
    /**
     * Start the mesh service
     */
    override fun startServices() {
        // Prevent double starts (defensive programming)
        if (isActive) {
            Log.w(TAG, "Mesh service already active, ignoring duplicate start request")
            return
        }
        if (terminated) {
            // This instance's scope was cancelled previously; refuse to start to avoid using dead scopes.
            Log.e(TAG, "Mesh service instance was terminated; create a new instance instead of restarting")
            return
        }
        
        Log.i(TAG, "Starting Bluetooth mesh service with peer ID: $myPeerID")
        
        if (connectionManager.startServices()) {
            isActive = true
            meshCore.startCore()
            Log.d(TAG, "MeshCore started")
        } else {
            Log.e(TAG, "Failed to start Bluetooth services")
        }
    }
    
    /**
     * Stop all mesh services
     */
    override fun stopServices() {
        if (!isActive) {
            Log.w(TAG, "Mesh service not active, ignoring stop request")
            return
        }
        
        Log.i(TAG, "Stopping Bluetooth mesh service")
        isActive = false

        // Unregister from bridge
        TransportBridgeService.unregister("BLE")
        
        // Send leave announcement
        meshCore.sendLeaveAnnouncement()
        
        serviceScope.launch {
            Log.d(TAG, "Stopping subcomponents and cancelling scope...")
            delay(200) // Give leave message time to send
            
            // Stop all components
            meshCore.stopCore()
            Log.d(TAG, "MeshCore stopped")
            connectionManager.stopServices()
            Log.d(TAG, "BluetoothConnectionManager stop requested")
            meshCore.shutdown()
            
            // Mark this instance as terminated and cancel its scope so it won't be reused
            terminated = true
            serviceScope.cancel()
            Log.i(TAG, "BluetoothMeshService terminated and scope cancelled")
        }
    }

    /**
     * Whether this instance can be safely reused. Returns false after stopServices() or if
     * any critical internal scope has been cancelled.
     */
    fun isReusable(): Boolean {
        val reusable = !terminated && serviceScope.isActive && connectionManager.isReusable()
        if (!reusable) {
            Log.d(TAG, "isReusable=false (terminated=$terminated, scopeActive=${serviceScope.isActive}, connReusable=${connectionManager.isReusable()})")
        }
        return reusable
    }
    
    /**
     * Send public message
     */
    override fun sendMessage(content: String, mentions: List<String>, channel: String?) {
        meshCore.sendMessage(content, mentions, channel)
    }

    /**
     * Send a file over mesh as a broadcast MESSAGE (public mesh timeline/channels).
     */
    override fun sendFileBroadcast(file: com.bitchat.android.model.BitchatFilePacket) {
        meshCore.sendFileBroadcast(file)
    }

    /**
     * Send a file as an encrypted private message using Noise protocol
     */
    override fun sendFilePrivate(recipientPeerID: String, file: com.bitchat.android.model.BitchatFilePacket) {
        meshCore.sendFilePrivate(recipientPeerID, file)
    }

    override fun cancelFileTransfer(transferId: String): Boolean {
        return meshCore.cancelFileTransfer(transferId)
    }

    // Local helper to hash payloads to a stable hex ID for progress mapping
    private fun sha256Hex(bytes: ByteArray): String = try {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(bytes)
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { bytes.size.toString(16) }
    
    /**
     * Send private message - SIMPLIFIED iOS-compatible version 
     * Uses NoisePayloadType system exactly like iOS SimplifiedBluetoothService
     */
    override fun sendPrivateMessage(content: String, recipientPeerID: String, recipientNickname: String, messageID: String?) {
        meshCore.sendPrivateMessage(content, recipientPeerID, recipientNickname, messageID)
    }
    
    /**
     * Send read receipt for a received private message - NEW NoisePayloadType implementation
     * Uses same encryption approach as iOS SimplifiedBluetoothService
     */
    override fun sendReadReceipt(messageID: String, recipientPeerID: String, readerNickname: String) {
        meshCore.sendReadReceipt(messageID, recipientPeerID, readerNickname)
    }
    
    /**
     * Send broadcast announce with TLV-encoded identity announcement - exactly like iOS
     */
    override fun sendBroadcastAnnounce() {
        meshCore.sendBroadcastAnnounce()
    }
    
    /**
     * Send announcement to specific peer with TLV-encoded identity announcement - exactly like iOS
     */
    override fun sendAnnouncementToPeer(peerID: String) {
        meshCore.sendAnnouncementToPeer(peerID)
    }

    /**
     * Get peer nicknames
     */
    override fun getPeerNicknames(): Map<String, String> = meshCore.getPeerNicknames()
    
    /**
     * Get peer RSSI values  
     */
    override fun getPeerRSSI(): Map<String, Int> = meshCore.getPeerRSSI()
    
    /**
     * Check if we have an established Noise session with a peer  
     */
    override fun hasEstablishedSession(peerID: String): Boolean {
        return meshCore.hasEstablishedSession(peerID)
    }
    
    /**
     * Get session state for a peer (for UI state display)
     */
    override fun getSessionState(peerID: String): com.bitchat.android.noise.NoiseSession.NoiseSessionState {
        return meshCore.getSessionState(peerID)
    }
    
    /**
     * Initiate Noise handshake with a specific peer (public API)
     */
    override fun initiateNoiseHandshake(peerID: String) {
        meshCore.initiateNoiseHandshake(peerID)
    }
    
    /**
     * Get peer fingerprint for identity management
     */
    override fun getPeerFingerprint(peerID: String): String? {
        return meshCore.getPeerFingerprint(peerID)
    }

    /**
     * Get current active peer count (for status/notifications)
     */
    override fun getActivePeerCount(): Int {
        return meshCore.getActivePeerCount()
    }

    /**
     * Get peer info for verification purposes
     */
    override fun getPeerInfo(peerID: String): PeerInfo? {
        return meshCore.getPeerInfo(peerID)
    }

    /**
     * Update peer information with verification data
     */
    override fun updatePeerInfo(
        peerID: String,
        nickname: String,
        noisePublicKey: ByteArray,
        signingPublicKey: ByteArray,
        isVerified: Boolean
    ): Boolean {
        return meshCore.updatePeerInfo(peerID, nickname, noisePublicKey, signingPublicKey, isVerified)
    }
    
    /**
     * Get our identity fingerprint
     */
    override fun getIdentityFingerprint(): String {
        return meshCore.getIdentityFingerprint()
    }
    
    /**
     * Check if encryption icon should be shown for a peer
     */
    override fun shouldShowEncryptionIcon(peerID: String): Boolean {
        return meshCore.shouldShowEncryptionIcon(peerID)
    }
    
    /**
     * Get all peers with established encrypted sessions
     */
    override fun getEncryptedPeers(): List<String> {
        return meshCore.getEncryptedPeers()
    }
    
    /**
     * Get device address for a specific peer ID
     */
    override fun getDeviceAddressForPeer(peerID: String): String? {
        return meshCore.getDeviceAddressForPeer(peerID)
    }
    
    /**
     * Get all device addresses mapped to their peer IDs
     */
    override fun getDeviceAddressToPeerMapping(): Map<String, String> {
        return meshCore.getDeviceAddressToPeerMapping()
    }
    
    /**
     * Print device addresses for all connected peers
     */
    override fun printDeviceAddressesForPeers(): String {
        return meshCore.getDebugInfoWithDeviceAddresses(connectionManager.addressPeerMap)
    }

    /**
     * Get debug status information
     */
    override fun getDebugStatus(): String {
        return meshCore.getDebugStatus(
            transportInfo = connectionManager.getDebugInfo(),
            deviceMap = connectionManager.addressPeerMap,
            extraLines = listOf(meshCore.getFingerprintDebugInfo()),
            title = "Bluetooth Mesh Service Debug Status"
        )
    }
    
    // MARK: - Panic Mode Support
    
    /**
     * Clear all internal mesh service data (for panic mode)
     */
    override fun clearAllInternalData() {
        Log.w(TAG, "🚨 Clearing all mesh service internal data")
        try {
            meshCore.clearAllInternalData()
            Log.d(TAG, "✅ Cleared all mesh service internal data")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error clearing mesh service internal data: ${e.message}")
        }
    }
    
    /**
     * Clear all encryption and cryptographic data (for panic mode)
     */
    override fun clearAllEncryptionData() {
        Log.w(TAG, "🚨 Clearing all encryption data")
        try {
            meshCore.clearAllEncryptionData()
            Log.d(TAG, "✅ Cleared all encryption data")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error clearing encryption data: ${e.message}")
        }
    }

    private inner class BleMeshTransport : MeshTransport {
        override val id: String = "BLE"
        var connectionManager: BluetoothConnectionManager? = null

        override fun broadcastPacket(routed: RoutedPacket) {
            connectionManager?.broadcastPacket(routed)
        }

        override fun sendPacketToPeer(peerID: String, packet: BitchatPacket) {
            connectionManager?.sendPacketToPeer(peerID, packet)
        }

        override fun cancelTransfer(transferId: String): Boolean {
            return connectionManager?.cancelTransfer(transferId) ?: false
        }

        override fun getDeviceAddressForPeer(peerID: String): String? {
            return connectionManager?.addressPeerMap?.entries?.find { it.value == peerID }?.key
        }

        override fun getDeviceAddressToPeerMapping(): Map<String, String> {
            return connectionManager?.addressPeerMap?.toMap() ?: emptyMap()
        }

        override fun getTransportDebugInfo(): String {
            return connectionManager?.getDebugInfo() ?: ""
        }
    }
}
