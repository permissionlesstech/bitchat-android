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

    // Visibility of the debug sheet; gates heavy work
    private val _debugSheetVisible = MutableStateFlow(false)
    val debugSheetVisible: StateFlow<Boolean> = _debugSheetVisible.asStateFlow()
    fun setDebugSheetVisible(visible: Boolean) { _debugSheetVisible.value = visible }

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
    // Per-device and per-peer rolling timestamps for stacked graphs
    private val perDeviceRelayTimestamps = mutableMapOf<String, ConcurrentLinkedQueue<Long>>()
    private val perPeerRelayTimestamps = mutableMapOf<String, ConcurrentLinkedQueue<Long>>()

    // Additional buckets to split incoming vs outgoing
    private val incomingTimestamps = ConcurrentLinkedQueue<Long>()
    private val outgoingTimestamps = ConcurrentLinkedQueue<Long>()
    private val perDeviceIncoming = mutableMapOf<String, ConcurrentLinkedQueue<Long>>()
    private val perDeviceOutgoing = mutableMapOf<String, ConcurrentLinkedQueue<Long>>()
    private val perPeerIncoming = mutableMapOf<String, ConcurrentLinkedQueue<Long>>()
    private val perPeerOutgoing = mutableMapOf<String, ConcurrentLinkedQueue<Long>>()

    // Expose current per-second rates (updated when logging/pruning occurs)
    private val _perDeviceLastSecond: MutableStateFlow<Map<String, Int>> = MutableStateFlow(emptyMap())
    val perDeviceLastSecond: StateFlow<Map<String, Int>> = _perDeviceLastSecond.asStateFlow()
    private val _perPeerLastSecond: MutableStateFlow<Map<String, Int>> = MutableStateFlow(emptyMap())
    val perPeerLastSecond: StateFlow<Map<String, Int>> = _perPeerLastSecond.asStateFlow()
    // New flows used by UI for incoming/outgoing stacked plots
    private val _perDeviceIncomingLastSecond: MutableStateFlow<Map<String, Int>> = MutableStateFlow(emptyMap())
    val perDeviceIncomingLastSecond: StateFlow<Map<String, Int>> = _perDeviceIncomingLastSecond.asStateFlow()
    private val _perDeviceOutgoingLastSecond: MutableStateFlow<Map<String, Int>> = MutableStateFlow(emptyMap())
    val perDeviceOutgoingLastSecond: StateFlow<Map<String, Int>> = _perDeviceOutgoingLastSecond.asStateFlow()
    private val _perPeerIncomingLastSecond: MutableStateFlow<Map<String, Int>> = MutableStateFlow(emptyMap())
    val perPeerIncomingLastSecond: StateFlow<Map<String, Int>> = _perPeerIncomingLastSecond.asStateFlow()
    private val _perPeerOutgoingLastSecond: MutableStateFlow<Map<String, Int>> = MutableStateFlow(emptyMap())
    val perPeerOutgoingLastSecond: StateFlow<Map<String, Int>> = _perPeerOutgoingLastSecond.asStateFlow()

    // Per-minute counts per key
    private val _perDeviceIncomingLastMinute: MutableStateFlow<Map<String, Int>> = MutableStateFlow(emptyMap())
    val perDeviceIncomingLastMinute: StateFlow<Map<String, Int>> = _perDeviceIncomingLastMinute.asStateFlow()
    private val _perDeviceOutgoingLastMinute: MutableStateFlow<Map<String, Int>> = MutableStateFlow(emptyMap())
    val perDeviceOutgoingLastMinute: StateFlow<Map<String, Int>> = _perDeviceOutgoingLastMinute.asStateFlow()
    private val _perPeerIncomingLastMinute: MutableStateFlow<Map<String, Int>> = MutableStateFlow(emptyMap())
    val perPeerIncomingLastMinute: StateFlow<Map<String, Int>> = _perPeerIncomingLastMinute.asStateFlow()
    private val _perPeerOutgoingLastMinute: MutableStateFlow<Map<String, Int>> = MutableStateFlow(emptyMap())
    val perPeerOutgoingLastMinute: StateFlow<Map<String, Int>> = _perPeerOutgoingLastMinute.asStateFlow()

    // Totals per key (since app start)
    private val deviceIncomingTotalsMap = mutableMapOf<String, Long>()
    private val deviceOutgoingTotalsMap = mutableMapOf<String, Long>()
    private val peerIncomingTotalsMap = mutableMapOf<String, Long>()
    private val peerOutgoingTotalsMap = mutableMapOf<String, Long>()
    private val _perDeviceIncomingTotalsFlow: MutableStateFlow<Map<String, Long>> = MutableStateFlow(emptyMap())
    val perDeviceIncomingTotal: StateFlow<Map<String, Long>> = _perDeviceIncomingTotalsFlow.asStateFlow()
    private val _perDeviceOutgoingTotalsFlow: MutableStateFlow<Map<String, Long>> = MutableStateFlow(emptyMap())
    val perDeviceOutgoingTotal: StateFlow<Map<String, Long>> = _perDeviceOutgoingTotalsFlow.asStateFlow()
    private val _perPeerIncomingTotalsFlow: MutableStateFlow<Map<String, Long>> = MutableStateFlow(emptyMap())
    val perPeerIncomingTotal: StateFlow<Map<String, Long>> = _perPeerIncomingTotalsFlow.asStateFlow()
    private val _perPeerOutgoingTotalsFlow: MutableStateFlow<Map<String, Long>> = MutableStateFlow(emptyMap())
    val perPeerOutgoingTotal: StateFlow<Map<String, Long>> = _perPeerOutgoingTotalsFlow.asStateFlow()
    
    // Internal data storage for managing debug data
    private val debugMessageQueue = ConcurrentLinkedQueue<DebugMessage>()
    private val scanResultsQueue = ConcurrentLinkedQueue<DebugScanResult>()
    
    private fun updateRelayStatsFromTimestamps() {
        if (!_debugSheetVisible.value) return
        val now = System.currentTimeMillis()
        // prune older than 15m
        while (true) {
            val head = relayTimestamps.peek() ?: break
            if (now - head > 15 * 60 * 1000L) {
                relayTimestamps.poll()
            } else break
        }
        // prune per-device and per-peer and compute 1s/60s rates
        fun pruneAndCount1s(map: MutableMap<String, ConcurrentLinkedQueue<Long>>): Map<String, Int> {
            val result = mutableMapOf<String, Int>()
            val iterator = map.entries.iterator()
            while (iterator.hasNext()) {
                val (key, q) = iterator.next()
                // prune this queue
                while (true) {
                    val ts = q.peek() ?: break
                    if (now - ts > 15 * 60 * 1000L) {
                        q.poll()
                    } else break
                }
                // count last 1s only
                val count1s = q.count { now - it <= 1_000L }
                if (q.isEmpty()) {
                    // cleanup empty queues to prevent unbounded growth
                    iterator.remove()
                }
                if (count1s > 0) result[key] = count1s
            }
            return result
        }
        fun pruneAndCount60s(map: MutableMap<String, ConcurrentLinkedQueue<Long>>): Map<String, Int> {
            val result = mutableMapOf<String, Int>()
            map.forEach { (key, q) ->
                val count60 = q.count { now - it <= 60_000L }
                if (count60 > 0) result[key] = count60
            }
            return result
        }

        val perDevice1s = pruneAndCount1s(perDeviceRelayTimestamps)
        val perPeer1s = pruneAndCount1s(perPeerRelayTimestamps)

        _perDeviceLastSecond.value = perDevice1s
        _perPeerLastSecond.value = perPeer1s
        // Also compute incoming/outgoing per-key rates
        _perDeviceIncomingLastSecond.value = pruneAndCount1s(perDeviceIncoming)
        _perDeviceOutgoingLastSecond.value = pruneAndCount1s(perDeviceOutgoing)
        _perPeerIncomingLastSecond.value = pruneAndCount1s(perPeerIncoming)
        _perPeerOutgoingLastSecond.value = pruneAndCount1s(perPeerOutgoing)
        _perDeviceIncomingLastMinute.value = pruneAndCount60s(perDeviceIncoming)
        _perDeviceOutgoingLastMinute.value = pruneAndCount60s(perDeviceOutgoing)
        _perPeerIncomingLastMinute.value = pruneAndCount60s(perPeerIncoming)
        _perPeerOutgoingLastMinute.value = pruneAndCount60s(perPeerOutgoing)
        val last1s = relayTimestamps.count { now - it <= 1_000L }
        val last10s = relayTimestamps.count { now - it <= 10_000L }
        val last1m = relayTimestamps.count { now - it <= 60_000L }
        val last15m = relayTimestamps.size
        // And incoming/outgoing per-second counters
        val last1sIncoming = incomingTimestamps.count { now - it <= 1_000L }
        val last1sOutgoing = outgoingTimestamps.count { now - it <= 1_000L }
        val last10sIncoming = incomingTimestamps.count { now - it <= 10_000L }
        val last10sOutgoing = outgoingTimestamps.count { now - it <= 10_000L }
        val last1mIncoming = incomingTimestamps.count { now - it <= 60_000L }
        val last1mOutgoing = outgoingTimestamps.count { now - it <= 60_000L }
        val last15mIncoming = incomingTimestamps.size
        val last15mOutgoing = outgoingTimestamps.size
        val totalIncoming = _relayStats.value.totalIncomingCount
        val totalOutgoing = _relayStats.value.totalOutgoingCount
        _relayStats.value = PacketRelayStats(
            totalRelaysCount = totalIncoming + totalOutgoing,
            lastSecondRelays = last1s,
            last10SecondRelays = last10s,
            lastMinuteRelays = last1m,
            last15MinuteRelays = last15m,
            lastResetTime = _relayStats.value.lastResetTime,
            lastSecondIncoming = last1sIncoming,
            lastSecondOutgoing = last1sOutgoing,
            last10SecondIncoming = last10sIncoming,
            last10SecondOutgoing = last10sOutgoing,
            lastMinuteIncoming = last1mIncoming,
            lastMinuteOutgoing = last1mOutgoing,
            last15MinuteIncoming = last15mIncoming,
            last15MinuteOutgoing = last15mOutgoing,
            totalIncomingCount = totalIncoming,
            totalOutgoingCount = totalOutgoing
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

        // Do not update counters here; this path is for readable logs only.
    }

    // Explicit incoming/outgoing logging to avoid double counting
    fun logIncoming(packetType: String, fromPeerID: String?, fromNickname: String?, fromDeviceAddress: String?) {
        if (verboseLoggingEnabled.value) {
            val who = fromNickname ?: fromPeerID ?: "unknown"
            addDebugMessage(DebugMessage.PacketEvent("📥 Incoming $packetType from $who (${fromPeerID ?: "?"}, ${fromDeviceAddress ?: "?"})"))
        }
        val now = System.currentTimeMillis()
        val visible = _debugSheetVisible.value
        if (visible) incomingTimestamps.offer(now)
        fromDeviceAddress?.let {
            perDeviceIncoming.getOrPut(it) { ConcurrentLinkedQueue() }.offer(now)
            deviceIncomingTotalsMap[it] = (deviceIncomingTotalsMap[it] ?: 0L) + 1L
            _perDeviceIncomingTotalsFlow.value = deviceIncomingTotalsMap.toMap()
        }
        fromPeerID?.let {
            perPeerIncoming.getOrPut(it) { ConcurrentLinkedQueue() }.offer(now)
            peerIncomingTotalsMap[it] = (peerIncomingTotalsMap[it] ?: 0L) + 1L
            _perPeerIncomingTotalsFlow.value = peerIncomingTotalsMap.toMap()
        }
        // bump totals
        val cur = _relayStats.value
        _relayStats.value = cur.copy(
            totalIncomingCount = cur.totalIncomingCount + 1,
            totalRelaysCount = cur.totalRelaysCount + 1
        )
        if (visible) updateRelayStatsFromTimestamps()
    }

    fun logOutgoing(packetType: String, toPeerID: String?, toNickname: String?, toDeviceAddress: String?, previousHopPeerID: String? = null) {
        if (verboseLoggingEnabled.value) {
            val who = toNickname ?: toPeerID ?: "unknown"
            addDebugMessage(DebugMessage.PacketEvent("📤 Outgoing $packetType to $who (${toPeerID ?: "?"}, ${toDeviceAddress ?: "?"})"))
        }
        val now = System.currentTimeMillis()
        val visible = _debugSheetVisible.value
        if (visible) outgoingTimestamps.offer(now)
        toDeviceAddress?.let {
            perDeviceOutgoing.getOrPut(it) { ConcurrentLinkedQueue() }.offer(now)
            deviceOutgoingTotalsMap[it] = (deviceOutgoingTotalsMap[it] ?: 0L) + 1L
            _perDeviceOutgoingTotalsFlow.value = deviceOutgoingTotalsMap.toMap()
        }
        (toPeerID ?: previousHopPeerID)?.let {
            perPeerOutgoing.getOrPut(it) { ConcurrentLinkedQueue() }.offer(now)
            peerOutgoingTotalsMap[it] = (peerOutgoingTotalsMap[it] ?: 0L) + 1L
            _perPeerOutgoingTotalsFlow.value = peerOutgoingTotalsMap.toMap()
        }
        val cur = _relayStats.value
        _relayStats.value = cur.copy(
            totalOutgoingCount = cur.totalOutgoingCount + 1,
            totalRelaysCount = cur.totalRelaysCount + 1
        )
        if (visible) updateRelayStatsFromTimestamps()
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
    val lastResetTime: Date = Date(),
    val lastSecondIncoming: Int = 0,
    val lastSecondOutgoing: Int = 0,
    val last10SecondIncoming: Int = 0,
    val last10SecondOutgoing: Int = 0,
    val lastMinuteIncoming: Int = 0,
    val lastMinuteOutgoing: Int = 0,
    val last15MinuteIncoming: Int = 0,
    val last15MinuteOutgoing: Int = 0,
    val totalIncomingCount: Long = 0,
    val totalOutgoingCount: Long = 0
)
