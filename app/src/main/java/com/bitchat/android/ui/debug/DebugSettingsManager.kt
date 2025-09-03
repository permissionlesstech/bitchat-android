package com.bitchat.android.ui.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Date
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Debug settings manager for controlling debug features and collecting debug data
 */
class DebugSettingsManager private constructor() {
    // NOTE: This singleton is referenced from mesh layer. Keep in ui.debug but avoid Compose deps.
    
    companion object {
        @Volatile
        private var INSTANCE: DebugSettingsManager? = null
        
        fun getInstance(): DebugSettingsManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DebugSettingsManager().also { INSTANCE = it }
            }
        }
    }
    
    // Debug settings state
    private val _verboseLoggingEnabled = MutableStateFlow(false)
    val verboseLoggingEnabled: StateFlow<Boolean> = _verboseLoggingEnabled.asStateFlow()
    
    private val _gattServerEnabled = MutableStateFlow(true)
    val gattServerEnabled: StateFlow<Boolean> = _gattServerEnabled.asStateFlow()
    
    private val _gattClientEnabled = MutableStateFlow(true)
    val gattClientEnabled: StateFlow<Boolean> = _gattClientEnabled.asStateFlow()
    
    private val _packetRelayEnabled = MutableStateFlow(true)
    val packetRelayEnabled: StateFlow<Boolean> = _packetRelayEnabled.asStateFlow()
    
    // Debug data collections
    private val _debugMessages = MutableStateFlow<List<DebugMessage>>(emptyList())
    val debugMessages: StateFlow<List<DebugMessage>> = _debugMessages.asStateFlow()
    
    private val _scanResults = MutableStateFlow<List<DebugScanResult>>(emptyList())
    val scanResults: StateFlow<List<DebugScanResult>> = _scanResults.asStateFlow()
    
    private val _connectedDevices = MutableStateFlow<List<ConnectedDevice>>(emptyList())
    val connectedDevices: StateFlow<List<ConnectedDevice>> = _connectedDevices.asStateFlow()
    
    // Packet relay statistics
    private val _relayStats = MutableStateFlow(PacketRelayStats())
    val relayStats: StateFlow<PacketRelayStats> = _relayStats.asStateFlow()

    // Timestamps to compute rolling window stats
    private val relayTimestamps = ConcurrentLinkedQueue<Long>()
    
    // Internal data storage for managing debug data
    private val debugMessageQueue = ConcurrentLinkedQueue<DebugMessage>()
    private val scanResultsQueue = ConcurrentLinkedQueue<DebugScanResult>()
    
    private fun updateRelayStatsFromTimestamps() {
        val now = System.currentTimeMillis()
        // prune older than 15m
        while (true) {
            val head = relayTimestamps.peek() ?: break
            if (now - head > 15 * 60 * 1000L) {
                relayTimestamps.poll()
            } else break
        }
        val last10s = relayTimestamps.count { now - it <= 10_000L }
        val last1m = relayTimestamps.count { now - it <= 60_000L }
        val last15m = relayTimestamps.size
        val total = _relayStats.value.totalRelaysCount + 1
        _relayStats.value = PacketRelayStats(
            totalRelaysCount = total,
            last10SecondRelays = last10s,
            lastMinuteRelays = last1m,
            last15MinuteRelays = last15m,
            lastResetTime = _relayStats.value.lastResetTime
        )
    }
    
    // MARK: - Setting Controls
    
    fun setVerboseLoggingEnabled(enabled: Boolean) {
        _verboseLoggingEnabled.value = enabled
        if (enabled) {
            addDebugMessage(DebugMessage.SystemMessage("🔊 Verbose logging enabled"))
        } else {
            addDebugMessage(DebugMessage.SystemMessage("🔇 Verbose logging disabled"))
        }
    }
    
    fun setGattServerEnabled(enabled: Boolean) {
        _gattServerEnabled.value = enabled
        addDebugMessage(DebugMessage.SystemMessage(
            if (enabled) "🟢 GATT Server enabled" else "🔴 GATT Server disabled"
        ))
    }
    
    fun setGattClientEnabled(enabled: Boolean) {
        _gattClientEnabled.value = enabled
        addDebugMessage(DebugMessage.SystemMessage(
            if (enabled) "🟢 GATT Client enabled" else "🔴 GATT Client disabled"
        ))
    }
    
    fun setPacketRelayEnabled(enabled: Boolean) {
        _packetRelayEnabled.value = enabled
        addDebugMessage(DebugMessage.SystemMessage(
            if (enabled) "📡 Packet relay enabled" else "🚫 Packet relay disabled"
        ))
    }
    
    // MARK: - Debug Data Collection
    
    fun addDebugMessage(message: DebugMessage) {
        if (!verboseLoggingEnabled.value && message !is DebugMessage.SystemMessage) {
            return // Only show system messages when verbose logging is disabled
        }
        
        debugMessageQueue.offer(message)
        
        // Keep only last 200 messages to prevent memory issues
        while (debugMessageQueue.size > 200) {
            debugMessageQueue.poll()
        }
        
        _debugMessages.value = debugMessageQueue.toList()
    }
    
    fun addScanResult(scanResult: DebugScanResult) {
        scanResultsQueue.offer(scanResult)
        
        // Keep only last 100 scan results
        while (scanResultsQueue.size > 100) {
            scanResultsQueue.poll()
        }
        
        _scanResults.value = scanResultsQueue.toList()
    }
    
    fun updateConnectedDevices(devices: List<ConnectedDevice>) {
        _connectedDevices.value = devices
    }
    
    fun updateRelayStats(stats: PacketRelayStats) {
        _relayStats.value = stats
    }
    
    // MARK: - Debug Message Creation Helpers
    
    fun logPeerConnection(peerID: String, nickname: String, deviceID: String, isInbound: Boolean) {
        if (verboseLoggingEnabled.value) {
            val direction = if (isInbound) "connected to our server" else "we connected as client"
            addDebugMessage(DebugMessage.PeerEvent(
                "🔗 $nickname ($peerID) $direction via device $deviceID"
            ))
        }
    }
    
    fun logPeerDisconnection(peerID: String, nickname: String, deviceID: String) {
        if (verboseLoggingEnabled.value) {
            addDebugMessage(DebugMessage.PeerEvent(
                "❌ $nickname ($peerID) disconnected from device $deviceID"
            ))
        }
    }
    
    fun logIncomingPacket(senderPeerID: String, senderNickname: String?, messageType: String, viaDeviceId: String?) {
        if (verboseLoggingEnabled.value) {
            val who = if (!senderNickname.isNullOrBlank()) "$senderNickname ($senderPeerID)" else senderPeerID
            val routeInfo = if (!viaDeviceId.isNullOrBlank()) " via $viaDeviceId" else " (direct)"
            addDebugMessage(DebugMessage.PacketEvent(
                "📥 Received $messageType from $who$routeInfo"
            ))
        }
    }
    
    fun logPacketRelay(packetType: String, originalPeerID: String, originalNickname: String?, viaDeviceId: String?) {
        if (verboseLoggingEnabled.value) {
            val who = if (!originalNickname.isNullOrBlank()) "$originalNickname ($originalPeerID)" else originalPeerID
            val routeInfo = if (!viaDeviceId.isNullOrBlank()) " via $viaDeviceId" else ""
            addDebugMessage(DebugMessage.RelayEvent(
                "📡 Relayed $packetType from $who$routeInfo"
            ))
        }
        // Update rolling statistics
        relayTimestamps.offer(System.currentTimeMillis())
        updateRelayStatsFromTimestamps()
    }
    
    // MARK: - Clear Data
    
    fun clearDebugMessages() {
        debugMessageQueue.clear()
        _debugMessages.value = emptyList()
        addDebugMessage(DebugMessage.SystemMessage("🗑️ Debug messages cleared"))
    }
    
    fun clearScanResults() {
        scanResultsQueue.clear()
        _scanResults.value = emptyList()
        addDebugMessage(DebugMessage.SystemMessage("🗑️ Scan results cleared"))
    }
}

// MARK: - Data Models

/**
 * Different types of debug messages for categorization and formatting
 */
sealed class DebugMessage(val content: String, val timestamp: Date = Date()) {
    class SystemMessage(content: String) : DebugMessage("⚙️ $content")
    class PeerEvent(content: String) : DebugMessage(content)
    class PacketEvent(content: String) : DebugMessage(content)
    class RelayEvent(content: String) : DebugMessage(content)
}

/**
 * Scan result data for debugging
 */
data class DebugScanResult(
    val deviceName: String?,
    val deviceAddress: String,
    val rssi: Int,
    val peerID: String?,
    val timestamp: Date = Date()
)

/**
 * Connected device information for debugging
 */
data class ConnectedDevice(
    val deviceAddress: String,
    val peerID: String?,
    val nickname: String?,
    val rssi: Int?,
    val connectionType: ConnectionType,
    val isDirectConnection: Boolean
)

enum class ConnectionType {
    GATT_SERVER,
    GATT_CLIENT
}

/**
 * Packet relay statistics for monitoring network activity
 */
data class PacketRelayStats(
    val totalRelaysCount: Long = 0,
    val last10SecondRelays: Int = 0,
    val lastMinuteRelays: Int = 0,
    val last15MinuteRelays: Int = 0,
    val lastResetTime: Date = Date()
)
