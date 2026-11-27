package com.bitchat.android.ui.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.atomic.AtomicLong
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.util.toHexString

/**
 * Debug settings manager for controlling debug features and collecting debug data
 */
@Singleton
class DebugSettingsManager @Inject constructor(
    private val debugPreferences: DebugPreferenceManager,
) {
    // NOTE: This singleton is referenced from mesh layer. Keep in ui.debug but avoid Compose deps.
    
    /** Applies the transport master toggles; registered by the phone, absent on the watch. */
    @Volatile
    var transportToggles: TransportToggles? = null

    // Debug settings state
    private val _verboseLoggingEnabled = MutableStateFlow(false)
    val verboseLoggingEnabled: StateFlow<Boolean> = _verboseLoggingEnabled.asStateFlow()
    
    private val _gattServerEnabled = MutableStateFlow(true)
    val gattServerEnabled: StateFlow<Boolean> = _gattServerEnabled.asStateFlow()
    
    private val _gattClientEnabled = MutableStateFlow(true)
    val gattClientEnabled: StateFlow<Boolean> = _gattClientEnabled.asStateFlow()
    
    private val _packetRelayEnabled = MutableStateFlow(true)
    val packetRelayEnabled: StateFlow<Boolean> = _packetRelayEnabled.asStateFlow()

    // Master transport toggles
    private val _bleEnabled = MutableStateFlow(true)
    val bleEnabled: StateFlow<Boolean> = _bleEnabled.asStateFlow()

    private val _wifiAwareEnabled = MutableStateFlow(false)
    val wifiAwareEnabled: StateFlow<Boolean> = _wifiAwareEnabled.asStateFlow()

    // Master transport toggles
    private val _wifiAwareVerbose = MutableStateFlow(false)
    val wifiAwareVerbose: StateFlow<Boolean> = _wifiAwareVerbose.asStateFlow()

    // Visibility of the debug sheet; gates heavy work
    private val _debugSheetVisible = MutableStateFlow(false)
    val debugSheetVisible: StateFlow<Boolean> = _debugSheetVisible.asStateFlow()
    fun setDebugSheetVisible(visible: Boolean) {
        _debugSheetVisible.value = visible
        if (visible) {
            publishTotalsSnapshots()
            updateRelayStatsFromTimestamps()
        }
    }

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
            _verboseLoggingEnabled.value = debugPreferences.getVerboseLogging(false)
            _gattServerEnabled.value = debugPreferences.getGattServerEnabled(true)
            _gattClientEnabled.value = debugPreferences.getGattClientEnabled(true)
            _packetRelayEnabled.value = debugPreferences.getPacketRelayEnabled(true)
            _maxConnectionsOverall.value = debugPreferences.getMaxConnectionsOverall(8)
            _maxServerConnections.value = debugPreferences.getMaxConnectionsServer(8)
            _maxClientConnections.value = debugPreferences.getMaxConnectionsClient(8)
            // Transport toggles
            _bleEnabled.value = debugPreferences.getBleEnabled(true)
            _wifiAwareEnabled.value = debugPreferences.getWifiAwareEnabled(false)
            _wifiAwareVerbose.value = debugPreferences.getWifiAwareVerbose(false)
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

    /**
     * Zero-allocation per-second circular ring buffer for rolling packet-rate windows.
     * Recording costs a single synchronized array increment with zero boxing or queue allocations,
     * keeping rolling 1m/15m counters accurate even while the debug sheet is closed.
     */
    private class RollingSecondCounter(private val windowSeconds: Int) {
        private val epochs = LongArray(windowSeconds) { Long.MIN_VALUE }
        private val counts = IntArray(windowSeconds)

        @Synchronized
        fun record(nowSec: Long) {
            val idx = Math.floorMod(nowSec, windowSeconds.toLong()).toInt()
            if (epochs[idx] != nowSec) {
                epochs[idx] = nowSec
                counts[idx] = 1
            } else {
                counts[idx]++
            }
        }

        @Synchronized
        fun sumLast(nowSec: Long, seconds: Int): Int {
            val clamped = minOf(seconds, windowSeconds)
            val cutoff = nowSec - clamped + 1
            var total = 0
            for (i in 0 until windowSeconds) {
                val ep = epochs[i]
                if (ep >= cutoff && ep <= nowSec) {
                    total += counts[i]
                }
            }
            return total
        }

        @Synchronized
        fun hasRecent(nowSec: Long): Boolean = sumLast(nowSec, windowSeconds) > 0
    }

    // Rolling window ring buffers (15m global; 60s per-device/per-peer)
    private val relayWindow = RollingSecondCounter(15 * 60)
    private val incomingWindow = RollingSecondCounter(15 * 60)
    private val outgoingWindow = RollingSecondCounter(15 * 60)
    private val perDeviceRelayWindows = ConcurrentHashMap<String, RollingSecondCounter>()
    private val perPeerRelayWindows = ConcurrentHashMap<String, RollingSecondCounter>()
    private val perDeviceIncomingWindows = ConcurrentHashMap<String, RollingSecondCounter>()
    private val perDeviceOutgoingWindows = ConcurrentHashMap<String, RollingSecondCounter>()
    private val perPeerIncomingWindows = ConcurrentHashMap<String, RollingSecondCounter>()
    private val perPeerOutgoingWindows = ConcurrentHashMap<String, RollingSecondCounter>()

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

    // Totals per key (since app start) — thread-safe atomics, snapshotted only while debug sheet is visible
    private val totalIncomingCountAtomic = AtomicLong(0L)
    private val totalOutgoingCountAtomic = AtomicLong(0L)
    private val deviceIncomingTotalsMap = ConcurrentHashMap<String, AtomicLong>()
    private val deviceOutgoingTotalsMap = ConcurrentHashMap<String, AtomicLong>()
    private val peerIncomingTotalsMap = ConcurrentHashMap<String, AtomicLong>()
    private val peerOutgoingTotalsMap = ConcurrentHashMap<String, AtomicLong>()
    private val _perDeviceIncomingTotalsFlow: MutableStateFlow<Map<String, Long>> = MutableStateFlow(emptyMap())
    val perDeviceIncomingTotal: StateFlow<Map<String, Long>> = _perDeviceIncomingTotalsFlow.asStateFlow()
    private val _perDeviceOutgoingTotalsFlow: MutableStateFlow<Map<String, Long>> = MutableStateFlow(emptyMap())
    val perDeviceOutgoingTotal: StateFlow<Map<String, Long>> = _perDeviceOutgoingTotalsFlow.asStateFlow()
    private val _perPeerIncomingTotalsFlow: MutableStateFlow<Map<String, Long>> = MutableStateFlow(emptyMap())
    val perPeerIncomingTotal: StateFlow<Map<String, Long>> = _perPeerIncomingTotalsFlow.asStateFlow()
    private val _perPeerOutgoingTotalsFlow: MutableStateFlow<Map<String, Long>> = MutableStateFlow(emptyMap())
    val perPeerOutgoingTotal: StateFlow<Map<String, Long>> = _perPeerOutgoingTotalsFlow.asStateFlow()

    private fun publishTotalsSnapshots() {
        _perDeviceIncomingTotalsFlow.value = deviceIncomingTotalsMap.entries.associate { it.key to it.value.get() }
        _perDeviceOutgoingTotalsFlow.value = deviceOutgoingTotalsMap.entries.associate { it.key to it.value.get() }
        _perPeerIncomingTotalsFlow.value = peerIncomingTotalsMap.entries.associate { it.key to it.value.get() }
        _perPeerOutgoingTotalsFlow.value = peerOutgoingTotalsMap.entries.associate { it.key to it.value.get() }
    }
    
    // Internal data storage for managing debug data
    private val debugMessageQueue = ConcurrentLinkedQueue<DebugMessage>()
    private val scanResultsQueue = ConcurrentLinkedQueue<DebugScanResult>()
    
    private fun updateRelayStatsFromTimestamps() {
        if (!_debugSheetVisible.value) return
        val nowSec = System.currentTimeMillis() / 1_000L

        fun snapshotAndPrune(
            map: ConcurrentHashMap<String, RollingSecondCounter>,
            windowSec: Int
        ): Map<String, Int> {
            val result = mutableMapOf<String, Int>()
            val iterator = map.entries.iterator()
            while (iterator.hasNext()) {
                val (key, counter) = iterator.next()
                val count = counter.sumLast(nowSec, windowSec)
                if (count > 0) {
                    result[key] = count
                } else if (!counter.hasRecent(nowSec)) {
                    iterator.remove()
                }
            }
            return result
        }

        val perDevice1s = snapshotAndPrune(perDeviceRelayWindows, 1)
        val perPeer1s = snapshotAndPrune(perPeerRelayWindows, 1)

        _perDeviceLastSecond.value = perDevice1s
        _perPeerLastSecond.value = perPeer1s
        // Also compute incoming/outgoing per-key rates
        _perDeviceIncomingLastSecond.value = snapshotAndPrune(perDeviceIncomingWindows, 1)
        _perDeviceOutgoingLastSecond.value = snapshotAndPrune(perDeviceOutgoingWindows, 1)
        _perPeerIncomingLastSecond.value = snapshotAndPrune(perPeerIncomingWindows, 1)
        _perPeerOutgoingLastSecond.value = snapshotAndPrune(perPeerOutgoingWindows, 1)
        _perDeviceIncomingLastMinute.value = snapshotAndPrune(perDeviceIncomingWindows, 60)
        _perDeviceOutgoingLastMinute.value = snapshotAndPrune(perDeviceOutgoingWindows, 60)
        _perPeerIncomingLastMinute.value = snapshotAndPrune(perPeerIncomingWindows, 60)
        _perPeerOutgoingLastMinute.value = snapshotAndPrune(perPeerOutgoingWindows, 60)
        val last1s = relayWindow.sumLast(nowSec, 1)
        val last10s = relayWindow.sumLast(nowSec, 10)
        val last1m = relayWindow.sumLast(nowSec, 60)
        val last15m = relayWindow.sumLast(nowSec, 15 * 60)
        // And incoming/outgoing counters
        val last1sIncoming = incomingWindow.sumLast(nowSec, 1)
        val last1sOutgoing = outgoingWindow.sumLast(nowSec, 1)
        val last10sIncoming = incomingWindow.sumLast(nowSec, 10)
        val last10sOutgoing = outgoingWindow.sumLast(nowSec, 10)
        val last1mIncoming = incomingWindow.sumLast(nowSec, 60)
        val last1mOutgoing = outgoingWindow.sumLast(nowSec, 60)
        val last15mIncoming = incomingWindow.sumLast(nowSec, 15 * 60)
        val last15mOutgoing = outgoingWindow.sumLast(nowSec, 15 * 60)
        val totalIncoming = totalIncomingCountAtomic.get()
        val totalOutgoing = totalOutgoingCountAtomic.get()
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
        debugPreferences.setVerboseLogging(enabled)
        _verboseLoggingEnabled.value = enabled
        if (enabled) {
            addDebugMessage(DebugMessage.SystemMessage("🔊 Verbose logging enabled"))
        } else {
            addDebugMessage(DebugMessage.SystemMessage("🔇 Verbose logging disabled"))
        }
    }
    
    fun setGattServerEnabled(enabled: Boolean) {
        debugPreferences.setGattServerEnabled(enabled)
        _gattServerEnabled.value = enabled
        addDebugMessage(DebugMessage.SystemMessage(
            if (enabled) "🟢 GATT Server enabled" else "🔴 GATT Server disabled"
        ))
    }
    
    fun setGattClientEnabled(enabled: Boolean) {
        debugPreferences.setGattClientEnabled(enabled)
        _gattClientEnabled.value = enabled
        addDebugMessage(DebugMessage.SystemMessage(
            if (enabled) "🟢 GATT Client enabled" else "🔴 GATT Client disabled"
        ))
    }
    
    fun setPacketRelayEnabled(enabled: Boolean) {
        debugPreferences.setPacketRelayEnabled(enabled)
        _packetRelayEnabled.value = enabled
        addDebugMessage(DebugMessage.SystemMessage(
            if (enabled) "📡 Packet relay enabled" else "🚫 Packet relay disabled"
        ))
    }

    fun setBleEnabled(enabled: Boolean) {
        debugPreferences.setBleEnabled(enabled)
        _bleEnabled.value = enabled
        addDebugMessage(DebugMessage.SystemMessage(if (enabled) "🟢 BLE enabled" else "🔴 BLE disabled"))
        try {
            transportToggles?.setBleEnabled(enabled)
        } catch (_: Exception) { }
    }

    fun setWifiAwareEnabled(enabled: Boolean) {
        debugPreferences.setWifiAwareEnabled(enabled)
        _wifiAwareEnabled.value = enabled
        addDebugMessage(DebugMessage.SystemMessage(if (enabled) "🟢 Wi‑Fi Aware enabled" else "🔴 Wi‑Fi Aware disabled"))
        try {
            transportToggles?.setWifiAwareEnabled(enabled)
        } catch (_: Exception) { }
    }

    fun setWifiAwareVerbose(enabled: Boolean) {
        debugPreferences.setWifiAwareVerbose(enabled)
        _wifiAwareVerbose.value = enabled
        addDebugMessage(DebugMessage.SystemMessage(if (enabled) "🔊 Wi‑Fi Aware verbose logging enabled" else "🔇 Wi‑Fi Aware verbose logging disabled"))
    }

    fun setMaxConnectionsOverall(value: Int) {
        val clamped = value.coerceIn(1, 32)
        debugPreferences.setMaxConnectionsOverall(clamped)
        _maxConnectionsOverall.value = clamped
        addDebugMessage(DebugMessage.SystemMessage("🔢 Max overall connections set to $clamped"))
    }

    fun setMaxServerConnections(value: Int) {
        val clamped = value.coerceIn(1, 32)
        debugPreferences.setMaxConnectionsServer(clamped)
        _maxServerConnections.value = clamped
        addDebugMessage(DebugMessage.SystemMessage("🖥️ Max server connections set to $clamped"))
    }

    fun setMaxClientConnections(value: Int) {
        val clamped = value.coerceIn(1, 32)
        debugPreferences.setMaxConnectionsClient(clamped)
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
        if (!_debugSheetVisible.value) return
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

    // Wi‑Fi Aware debug collections
    private val _wifiAwareDiscovered = MutableStateFlow<Map<String, String>>(emptyMap()) // peerID->nickname
    val wifiAwareDiscovered: StateFlow<Map<String, String>> = _wifiAwareDiscovered.asStateFlow()

    private val _wifiAwareConnected = MutableStateFlow<Map<String, String>>(emptyMap()) // peerID->ip
    val wifiAwareConnected: StateFlow<Map<String, String>> = _wifiAwareConnected.asStateFlow()

    fun updateWifiAwareDiscovered(map: Map<String, String>) { _wifiAwareDiscovered.value = map }
    fun updateWifiAwareConnected(map: Map<String, String>) { _wifiAwareConnected.value = map }
    
    fun updateRelayStats(stats: PacketRelayStats) {
        _relayStats.value = stats
    }

    // Sync/GCS settings (UI-configurable)
    private val _seenPacketCapacity = MutableStateFlow(debugPreferences.getSeenPacketCapacity(500))
    val seenPacketCapacity: StateFlow<Int> = _seenPacketCapacity.asStateFlow()

    private val _gcsMaxBytes = MutableStateFlow(debugPreferences.getGcsMaxFilterBytes(400))
    val gcsMaxBytes: StateFlow<Int> = _gcsMaxBytes.asStateFlow()

    private val _gcsFprPercent = MutableStateFlow(debugPreferences.getGcsFprPercent(1.0))
    val gcsFprPercent: StateFlow<Double> = _gcsFprPercent.asStateFlow()

    fun setSeenPacketCapacity(value: Int) {
        val clamped = value.coerceIn(10, 1000)
        debugPreferences.setSeenPacketCapacity(clamped)
        _seenPacketCapacity.value = clamped
        addDebugMessage(DebugMessage.SystemMessage("🧩 max packets per sync set to $clamped"))
    }

    fun setGcsMaxBytes(value: Int) {
        val clamped = value.coerceIn(128, 1024)
        debugPreferences.setGcsMaxFilterBytes(clamped)
        _gcsMaxBytes.value = clamped
        addDebugMessage(DebugMessage.SystemMessage("🌸 max GCS filter size set to $clamped bytes"))
    }

    fun setGcsFprPercent(value: Double) {
        val clamped = value.coerceIn(0.1, 5.0)
        debugPreferences.setGcsFprPercent(clamped)
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
        isRelay: Boolean = true,
        packetVersion: UByte = 1u,
        routeInfo: String? = null
    ) {
        // Fast path: do zero string formatting when verbose logging is off.
        if (!verboseLoggingEnabled.value) return

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
        val routeStr = if (routeInfo != null) " $routeInfo" else ""

        if (isRelay) {
            // Relay: show [previousPeer] -> [nextPeer]
            addDebugMessage(
                DebugMessage.RelayEvent(
                    "♻️ Relayed v$packetVersion $packetType by $senderLabel from $fromName (${fromPeerID ?: "?"}, $fromAddr) to $toName (${toPeerID ?: "?"}, $toAddr) with TTL $ttlStr$routeStr"
                )
            )
        } else {
            addDebugMessage(
                DebugMessage.PacketEvent(
                    "📤 Sent v$packetVersion $packetType by $senderLabel to $toName (${toPeerID ?: "?"}, $toAddr) with TTL $ttlStr$routeStr"
                )
            )
        }
    }

    // MARK: - Debug Events for Animation
    sealed class MeshVisualEvent {
        data class PacketActivity(val peerID: String) : MeshVisualEvent()
        data class RouteActivity(val route: List<String>) : MeshVisualEvent()
    }

    private val _meshVisualEvents = kotlinx.coroutines.flow.MutableSharedFlow<MeshVisualEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val meshVisualEvents: kotlinx.coroutines.flow.SharedFlow<MeshVisualEvent> = _meshVisualEvents.asSharedFlow()

    fun emitVisualEvent(event: MeshVisualEvent) {
        if (_debugSheetVisible.value) {
            _meshVisualEvents.tryEmit(event)
        }
    }

    // Peer nickname resolver
    private var nicknameResolver: ((String) -> String?)? = null
    fun setNicknameResolver(resolver: (String) -> String?) { nicknameResolver = resolver }
    
    // Explicit incoming/outgoing logging to avoid double counting
    fun logIncoming(packet: BitchatPacket, fromPeerID: String, fromNickname: String?, fromDeviceAddress: String?, myPeerID: String) {
        // Lightweight lock-free totals and zero-allocation ring buffers always stay accurate
        // even when the debug sheet is closed (no UI StateFlow updates while hidden).
        val nowSec = System.currentTimeMillis() / 1_000L
        totalIncomingCountAtomic.incrementAndGet()
        incomingWindow.record(nowSec)
        fromDeviceAddress?.let {
            deviceIncomingTotalsMap.computeIfAbsent(it) { AtomicLong(0L) }.incrementAndGet()
            perDeviceIncomingWindows.computeIfAbsent(it) { RollingSecondCounter(60) }.record(nowSec)
        }
        peerIncomingTotalsMap.computeIfAbsent(fromPeerID) { AtomicLong(0L) }.incrementAndGet()
        perPeerIncomingWindows.computeIfAbsent(fromPeerID) { RollingSecondCounter(60) }.record(nowSec)

        val verbose = verboseLoggingEnabled.value
        val visible = _debugSheetVisible.value
        if (!verbose && !visible) return

        val route = packet.route
        if (verbose) {
            val packetType = packet.type.toString()
            val packetVersion = packet.version
            val routeStr = if (!route.isNullOrEmpty()) " routed: ${route.size} hops" else ""
            val resolvedNick = fromNickname ?: nicknameResolver?.invoke(fromPeerID) ?: "unknown"
            val who = if (resolvedNick != "unknown") "$resolvedNick ($fromPeerID)" else fromPeerID
            addDebugMessage(DebugMessage.PacketEvent("📥 Incoming v$packetVersion $packetType from $who (${fromDeviceAddress ?: "?"})$routeStr"))
        }

        if (!visible) return

        emitVisualEvent(MeshVisualEvent.PacketActivity(fromPeerID))
        if (!route.isNullOrEmpty()) {
            val fullRoute = ArrayList<String>(route.size + 2)
            fullRoute.add(packet.senderID.toHexString())
            route.forEach { fullRoute.add(it.toHexString()) }
            packet.recipientID?.let { fullRoute.add(it.toHexString()) }
            emitVisualEvent(MeshVisualEvent.RouteActivity(fullRoute))
        }

        if (fromDeviceAddress != null) {
            _perDeviceIncomingTotalsFlow.value = deviceIncomingTotalsMap.entries.associate { e -> e.key to e.value.get() }
        }
        _perPeerIncomingTotalsFlow.value = peerIncomingTotalsMap.entries.associate { e -> e.key to e.value.get() }
        updateRelayStatsFromTimestamps()
    }

    fun logOutgoing(packetType: String, toPeerID: String?, toNickname: String?, toDeviceAddress: String?, previousHopPeerID: String? = null, packetVersion: UByte = 1u, routeInfo: String? = null) {
        // Lightweight lock-free totals and zero-allocation ring buffers always stay accurate
        // even when the debug sheet is closed (no UI StateFlow updates while hidden).
        val nowSec = System.currentTimeMillis() / 1_000L
        totalOutgoingCountAtomic.incrementAndGet()
        outgoingWindow.record(nowSec)
        toDeviceAddress?.let {
            deviceOutgoingTotalsMap.computeIfAbsent(it) { AtomicLong(0L) }.incrementAndGet()
            perDeviceOutgoingWindows.computeIfAbsent(it) { RollingSecondCounter(60) }.record(nowSec)
        }
        val peerKey = toPeerID ?: previousHopPeerID
        peerKey?.let {
            peerOutgoingTotalsMap.computeIfAbsent(it) { AtomicLong(0L) }.incrementAndGet()
            perPeerOutgoingWindows.computeIfAbsent(it) { RollingSecondCounter(60) }.record(nowSec)
        }

        val verbose = verboseLoggingEnabled.value
        val visible = _debugSheetVisible.value
        if (!verbose && !visible) return

        if (verbose) {
            val who = toNickname ?: toPeerID ?: "unknown"
            val routeStr = if (routeInfo != null) " $routeInfo" else ""
            addDebugMessage(DebugMessage.PacketEvent("📤 Outgoing v$packetVersion $packetType to $who (${toPeerID ?: "?"}, ${toDeviceAddress ?: "?"})$routeStr"))
        }

        if (!visible) return

        if (toDeviceAddress != null) {
            _perDeviceOutgoingTotalsFlow.value = deviceOutgoingTotalsMap.entries.associate { e -> e.key to e.value.get() }
        }
        if (peerKey != null) {
            _perPeerOutgoingTotalsFlow.value = peerOutgoingTotalsMap.entries.associate { e -> e.key to e.value.get() }
        }
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

    /**
     * Empties the lists, maps and per-key statistics that hold peer IDs, nicknames and device
     * addresses, adding no message of its own. Used by a panic.
     */
    fun clearBuffers() {
        debugMessageQueue.clear()
        scanResultsQueue.clear()
        _debugMessages.value = emptyList()
        _scanResults.value = emptyList()
        _connectedDevices.value = emptyList()
        _wifiAwareDiscovered.value = emptyMap()
        _wifiAwareConnected.value = emptyMap()
        // Concurrent maps: a logger running alongside may add a key back, never corrupt one.
        listOf(
            perDeviceRelayWindows, perPeerRelayWindows, perDeviceIncomingWindows, perDeviceOutgoingWindows,
            perPeerIncomingWindows, perPeerOutgoingWindows,
        ).forEach { it.clear() }
        listOf(deviceIncomingTotalsMap, deviceOutgoingTotalsMap, peerIncomingTotalsMap, peerOutgoingTotalsMap)
            .forEach { it.clear() }
        listOf(
            _perDeviceLastSecond, _perPeerLastSecond,
            _perDeviceIncomingLastSecond, _perDeviceOutgoingLastSecond,
            _perPeerIncomingLastSecond, _perPeerOutgoingLastSecond,
            _perDeviceIncomingLastMinute, _perDeviceOutgoingLastMinute,
            _perPeerIncomingLastMinute, _perPeerOutgoingLastMinute,
        ).forEach { it.value = emptyMap() }
        listOf(
            _perDeviceIncomingTotalsFlow, _perDeviceOutgoingTotalsFlow,
            _perPeerIncomingTotalsFlow, _perPeerOutgoingTotalsFlow,
        ).forEach { it.value = emptyMap() }
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
