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

    // Connection limit overrides (debug)
    private val _maxConnectionsOverall = MutableStateFlow(8)
    val maxConnectionsOverall: StateFlow<Int> = _maxConnectionsOverall.asStateFlow()
    private val _maxServerConnections = MutableStateFlow(8)
    val maxServerConnections: StateFlow<Int> = _maxServerConnections.asStateFlow()
    private val _maxClientConnections = MutableStateFlow(8)
    val maxClientConnections: StateFlow<Int> = _maxClientConnections.asStateFlow()
    
    init {
        // Load persisted defaults (if preference manager already initialized)
        try {
            _verboseLoggingEnabled.value = DebugPreferenceManager.getVerboseLogging(false)
            _gattServerEnabled.value = DebugPreferenceManager.getGattServerEnabled(true)
            _gattClientEnabled.value = DebugPreferenceManager.getGattClientEnabled(true)
            _packetRelayEnabled.value = DebugPreferenceManager.getPacketRelayEnabled(true)
            _maxConnectionsOverall.value = DebugPreferenceManager.getMaxConnectionsOverall(8)
            _maxServerConnections.value = DebugPreferenceManager.getMaxConnectionsServer(8)
            _maxClientConnections.value = DebugPreferenceManager.getMaxConnectionsClient(8)
        } catch (_: Exception) {
            // Preferences not ready yet; keep defaults. They will be applied on first change.
        }
    }

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
        val last1s = relayTimestamps.count { now - it <= 1_000L }
        val last10s = relayTimestamps.count { now - it <= 10_000L }
        val last1m = relayTimestamps.count { now - it <= 60_000L }
        val last15m = relayTimestamps.size
        val total = _relayStats.value.totalRelaysCount + 1
        _relayStats.value = PacketRelayStats(
            totalRelaysCount = total,
            lastSecondRelays = last1s,
            last10SecondRelays = last10s,
            lastMinuteRelays = last1m,
            last15MinuteRelays = last15m,
            lastResetTime = _relayStats.value.lastResetTime
        )
    }
    
    // MARK: - Setting Controls
    
    fun setVerboseLoggingEnabled(enabled: Boolean) {
        DebugPreferenceManager.setVerboseLogging(enabled)
        _verboseLoggingEnabled.value = enabled
        if (enabled) {
            addDebugMessage(DebugMessage.SystemMessage("🔊 Verbose logging enabled"))
        } else {
            addDebugMessage(DebugMessage.SystemMessage("🔇 Verbose logging disabled"))
        }
    }
    
    fun setGattServerEnabled(enabled: Boolean) {
        DebugPreferenceManager.setGattServerEnabled(enabled)
        _gattServerEnabled.value = enabled
        addDebugMessage(DebugMessage.SystemMessage(
            if (enabled) "🟢 GATT Server enabled" else "🔴 GATT Server disabled"
        ))
    }
    
    fun setGattClientEnabled(enabled: Boolean) {
        DebugPreferenceManager.setGattClientEnabled(enabled)
        _gattClientEnabled.value = enabled
        addDebugMessage(DebugMessage.SystemMessage(
            if (enabled) "🟢 GATT Client enabled" else "🔴 GATT Client disabled"
        ))
    }
    
    fun setPacketRelayEnabled(enabled: Boolean) {
        DebugPreferenceManager.setPacketRelayEnabled(enabled)
        _packetRelayEnabled.value = enabled
        addDebugMessage(DebugMessage.SystemMessage(
            if (enabled) "📡 Packet relay enabled" else "🚫 Packet relay disabled"
        ))
    }

    fun setMaxConnectionsOverall(value: Int) {
        val clamped = value.coerceIn(1, 32)
        DebugPreferenceManager.setMaxConnectionsOverall(clamped)
        _maxConnectionsOverall.value = clamped
        addDebugMessage(DebugMessage.SystemMessage("🔢 Max overall connections set to $clamped"))
    }

    fun setMaxServerConnections(value: Int) {
        val clamped = value.coerceIn(1, 32)
        DebugPreferenceManager.setMaxConnectionsServer(clamped)
        _maxServerConnections.value = clamped
        addDebugMessage(DebugMessage.SystemMessage("🖥️ Max server connections set to $clamped"))
    }

    fun setMaxClientConnections(value: Int) {
        val clamped = value.coerceIn(1, 32)
        DebugPreferenceManager.setMaxConnectionsClient(clamped)
        _maxClientConnections.value = clamped
        addDebugMessage(DebugMessage.SystemMessage("📱 Max client connections set to $clamped"))
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
        // De-duplicate by device address; keep most recent
        if (scanResultsQueue.isNotEmpty()) {
            val toRemove = scanResultsQueue.filter { it.deviceAddress == scanResult.deviceAddress }
            toRemove.forEach { scanResultsQueue.remove(it) }
        }
        scanResultsQueue.offer(scanResult)

        // Keep only last 100 unique scan results
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

    // Sync/GCS settings (UI-configurable)
    private val _seenPacketCapacity = MutableStateFlow(DebugPreferenceManager.getSeenPacketCapacity(500))
    val seenPacketCapacity: StateFlow<Int> = _seenPacketCapacity.asStateFlow()

    private val _gcsMaxBytes = MutableStateFlow(DebugPreferenceManager.getGcsMaxFilterBytes(400))
    val gcsMaxBytes: StateFlow<Int> = _gcsMaxBytes.asStateFlow()

    private val _gcsFprPercent = MutableStateFlow(DebugPreferenceManager.getGcsFprPercent(1.0))
    val gcsFprPercent: StateFlow<Double> = _gcsFprPercent.asStateFlow()

    fun setSeenPacketCapacity(value: Int) {
        val clamped = value.coerceIn(10, 1000)
        DebugPreferenceManager.setSeenPacketCapacity(clamped)
        _seenPacketCapacity.value = clamped
        addDebugMessage(DebugMessage.SystemMessage("🧩 max packets per sync set to $clamped"))
    }

    fun setGcsMaxBytes(value: Int) {
        val clamped = value.coerceIn(128, 1024)
        DebugPreferenceManager.setGcsMaxFilterBytes(clamped)
        _gcsMaxBytes.value = clamped
        addDebugMessage(DebugMessage.SystemMessage("🌸 max GCS filter size set to $clamped bytes"))
    }

    fun setGcsFprPercent(value: Double) {
        val clamped = value.coerceIn(0.1, 5.0)
        DebugPreferenceManager.setGcsFprPercent(clamped)
        _gcsFprPercent.value = clamped
        addDebugMessage(DebugMessage.SystemMessage("🎯 GCS FPR set to ${String.format("%.2f", clamped)}%"))
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
                "📦 Received $messageType from $who$routeInfo"
            ))
        }
    }
    fun logPacketRelay(
        packetType: String,
        originalPeerID: String,
        originalNickname: String?,
        viaDeviceId: String?
    ) {
        // Backward-compatible simple API; delegate to detailed formatter with best effort
        logPacketRelayDetailed(
            packetType = packetType,
            senderPeerID = originalPeerID,
            senderNickname = originalNickname,
            fromPeerID = null,
            fromNickname = null,
            fromDeviceAddress = viaDeviceId,
            toPeerID = null,
            toNickname = null,
            toDeviceAddress = null,
            ttl = null,
            isRelay = true
        )
    }
    

    // New, more detailed relay logger used by the mesh/broadcaster
    fun logPacketRelayDetailed(
        packetType: String,
        senderPeerID: String?,
        senderNickname: String?,
        fromPeerID: String?,
        fromNickname: String?,
        fromDeviceAddress: String?,
        toPeerID: String?,
        toNickname: String?,
        toDeviceAddress: String?,
        ttl: UByte?,
        isRelay: Boolean = true
    ) {
        // Build message only if verbose logging is enabled, but always update stats
        val senderLabel = when {
            !senderNickname.isNullOrBlank() && !senderPeerID.isNullOrBlank() -> "$senderNickname ($senderPeerID)"
            !senderNickname.isNullOrBlank() -> senderNickname
            !senderPeerID.isNullOrBlank() -> senderPeerID
            else -> "unknown"
        }
        val fromName = when {
            !fromNickname.isNullOrBlank() -> fromNickname
            !fromPeerID.isNullOrBlank() -> fromPeerID
            else -> "unknown"
        }
        val toName = when {
            !toNickname.isNullOrBlank() -> toNickname
            !toPeerID.isNullOrBlank() -> toPeerID
            else -> "unknown"
        }

        val fromAddr = fromDeviceAddress ?: "?"
        val toAddr = toDeviceAddress ?: "?"
        val ttlStr = ttl?.toString() ?: "?"

        if (verboseLoggingEnabled.value) {
            if (isRelay) {
                addDebugMessage(
                    DebugMessage.RelayEvent(
                        "♻️ Relayed $packetType by $senderLabel from $fromName (${fromPeerID ?: "?"}, $fromAddr) to $toName (${toPeerID ?: "?"}, $toAddr) with TTL $ttlStr"
                    )
                )
            } else {
                addDebugMessage(
                    DebugMessage.PacketEvent(
                        "📤 Sent $packetType by $senderLabel to $toName (${toPeerID ?: "?"}, $toAddr) with TTL $ttlStr"
                    )
                )
            }
        }

        // Update rolling statistics only for relays
        if (isRelay) {
            relayTimestamps.offer(System.currentTimeMillis())
            updateRelayStatsFromTimestamps()
        }
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
    val lastSecondRelays: Int = 0,
    val last10SecondRelays: Int = 0,
    val lastMinuteRelays: Int = 0,
    val last15MinuteRelays: Int = 0,
    val lastResetTime: Date = Date()
)
