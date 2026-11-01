package com.bitchat.android.ui.debug

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitchat.android.settings.MeshDiagnostics
import com.bitchat.android.settings.TransportDiagnostics
import com.bitchat.android.share.AppBuildInfo
import com.bitchat.android.spam.SpamFilter
import com.bitchat.android.spam.SpamFilterPreferences
import com.bitchat.android.util.DistributionInfoProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug's state and the writes behind its controls.
 *
 * The state follows the manager's flows, the Wi-Fi Aware status and the gossip
 * graph. While the screen is shown, which the Root reports as started
 * ([DebugSettingsAction.ScreenShown] until [DebugSettingsAction.ScreenHidden],
 * so the background pauses it, B3), one job marks Debug visible and polls
 * once a second (inventory DE1, DE3), looking up the running mesh on every
 * poll. With no mesh it shows "no mesh" and never creates one (B1). The
 * relay graph's sampler runs alongside it, once a second, so the graph and its
 * colours do not depend on the card being on screen (B2). That job and the
 * visibility writes run in order on one background thread, so a rotation's
 * hide-then-show lands as false then true, as it always has.
 */
@HiltViewModel
class DebugSettingsViewModel internal constructor(
    private val manager: DebugSettingsManager,
    /** The spam filter controls the Debug screen draws. */
    internal val spamFilterPreferences: SpamFilterPreferences,
    internal val spamFilter: SpamFilter,
    /** Keeps the relay graph's mode and series, as the card's saveable state did. */
    private val savedState: SavedStateHandle,
    graph: MeshGraphSource,
    private val diagnostics: TransportDiagnostics,
    private val inspectDistribution: () -> DistributionInfoProvider.DistributionInfo,
    /** Where the mesh is opened and polled; tests pass their own dispatcher. */
    defaultDispatcher: CoroutineDispatcher,
    /** Where the installed package is inspected; tests pass their own dispatcher. */
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    @Inject
    constructor(
        manager: DebugSettingsManager,
        spamFilterPreferences: SpamFilterPreferences,
        spamFilter: SpamFilter,
        savedState: SavedStateHandle,
        graph: MeshGraphSource,
        diagnostics: TransportDiagnostics,
        buildInfo: AppBuildInfo,
        @ApplicationContext context: Context,
    ) : this(
        manager = manager,
        spamFilterPreferences = spamFilterPreferences,
        spamFilter = spamFilter,
        savedState = savedState,
        graph = graph,
        diagnostics = diagnostics,
        inspectDistribution = { DistributionInfoProvider.inspect(context, buildInfo) },
        defaultDispatcher = Dispatchers.Default,
        ioDispatcher = Dispatchers.IO,
    )

    /** The packet and route pulses the mesh graph animates. */
    val meshVisualEvents: Flow<DebugSettingsManager.MeshVisualEvent> get() = manager.meshVisualEvents

    // One worker at a time, in submission order.
    private val meshDispatcher = defaultDispatcher.limitedParallelism(1)

    // Touched only on meshDispatcher once the screen is shown.
    private val sampler = RelayGraphSampler(
        paletteSize = RELAY_PALETTE_SIZE,
        initial = relayGraphSeriesFrom(savedState[KEY_RELAY_SERIES]) ?: RelayGraphSeries(),
    )

    private val _uiState = MutableStateFlow(
        DebugSettingsUiState(
            verboseLogging = manager.verboseLoggingEnabled.value,
            gattServerEnabled = manager.gattServerEnabled.value,
            gattClientEnabled = manager.gattClientEnabled.value,
            packetRelayEnabled = manager.packetRelayEnabled.value,
            maxConnectionsOverall = manager.maxConnectionsOverall.value,
            maxServerConnections = manager.maxServerConnections.value,
            maxClientConnections = manager.maxClientConnections.value,
            debugMessages = manager.debugMessages.value,
            scanResults = manager.scanResults.value,
            connectedDevices = manager.connectedDevices.value,
            relayStats = manager.relayStats.value,
            seenPacketCapacity = manager.seenPacketCapacity.value,
            gcsMaxBytes = manager.gcsMaxBytes.value,
            gcsFprPercent = manager.gcsFprPercent.value,
            bleEnabled = manager.bleEnabled.value,
            wifiAwareEnabled = manager.wifiAwareEnabled.value,
            wifiAwareVerbose = manager.wifiAwareVerbose.value,
            wifiAwareDiscovered = manager.wifiAwareDiscovered.value,
            wifiAwareConnected = manager.wifiAwareConnected.value,
            wifiAwareStatus = diagnostics.wifiAwareStatus.value,
            wifiAwareLivePeers = diagnostics.wifiAwareConnectedPeers.value,
            wifiAwarePermissions = diagnostics.wifiAwarePermissions(),
            meshGraph = graph.graphState.value,
            perDeviceIncoming = RelayKeyCounts(
                manager.perDeviceIncomingLastSecond.value,
                manager.perDeviceIncomingLastMinute.value,
                manager.perDeviceIncomingTotal.value,
            ),
            perDeviceOutgoing = RelayKeyCounts(
                manager.perDeviceOutgoingLastSecond.value,
                manager.perDeviceOutgoingLastMinute.value,
                manager.perDeviceOutgoingTotal.value,
            ),
            perPeerIncoming = RelayKeyCounts(
                manager.perPeerIncomingLastSecond.value,
                manager.perPeerIncomingLastMinute.value,
                manager.perPeerIncomingTotal.value,
            ),
            perPeerOutgoing = RelayKeyCounts(
                manager.perPeerOutgoingLastSecond.value,
                manager.perPeerOutgoingLastMinute.value,
                manager.perPeerOutgoingTotal.value,
            ),
            relayGraphMode = savedState.get<String>(KEY_RELAY_MODE)
                ?.let { name -> GraphMode.entries.firstOrNull { it.name == name } }
                ?: GraphMode.OVERALL,
            relayGraph = sampler.series,
        )
    )
    val uiState: StateFlow<DebugSettingsUiState> = _uiState.asStateFlow()

    private val _events = Channel<DebugSettingsEvent>(Channel.BUFFERED)
    val events: Flow<DebugSettingsEvent> = _events.receiveAsFlow()

    private var shownJob: Job? = null
    private var samplerJob: Job? = null

    /** The mesh the latest poll found; null if there is none. */
    @Volatile
    private var mesh: MeshDiagnostics? = null

    init {
        follow(manager.verboseLoggingEnabled) { copy(verboseLogging = it) }
        follow(manager.gattServerEnabled) { copy(gattServerEnabled = it) }
        follow(manager.gattClientEnabled) { copy(gattClientEnabled = it) }
        follow(manager.packetRelayEnabled) { copy(packetRelayEnabled = it) }
        follow(manager.maxConnectionsOverall) { copy(maxConnectionsOverall = it) }
        follow(manager.maxServerConnections) { copy(maxServerConnections = it) }
        follow(manager.maxClientConnections) { copy(maxClientConnections = it) }
        follow(manager.debugMessages) { copy(debugMessages = it) }
        follow(manager.scanResults) { copy(scanResults = it) }
        follow(manager.connectedDevices) { copy(connectedDevices = it) }
        follow(manager.relayStats) { copy(relayStats = it) }
        follow(manager.seenPacketCapacity) { copy(seenPacketCapacity = it) }
        follow(manager.gcsMaxBytes) { copy(gcsMaxBytes = it) }
        follow(manager.gcsFprPercent) { copy(gcsFprPercent = it) }
        follow(manager.bleEnabled) { copy(bleEnabled = it) }
        follow(manager.wifiAwareEnabled) { copy(wifiAwareEnabled = it) }
        follow(manager.wifiAwareVerbose) { copy(wifiAwareVerbose = it) }
        follow(manager.wifiAwareDiscovered) { copy(wifiAwareDiscovered = it) }
        follow(manager.wifiAwareConnected) { copy(wifiAwareConnected = it) }
        follow(diagnostics.wifiAwareStatus) { copy(wifiAwareStatus = it) }
        follow(diagnostics.wifiAwareConnectedPeers) { copy(wifiAwareLivePeers = it) }
        follow(graph.graphState) { copy(meshGraph = it) }

        follow(manager.perDeviceIncomingLastSecond) { copy(perDeviceIncoming = perDeviceIncoming.copy(lastSecond = it)) }
        follow(manager.perDeviceIncomingLastMinute) { copy(perDeviceIncoming = perDeviceIncoming.copy(lastMinute = it)) }
        follow(manager.perDeviceIncomingTotal) { copy(perDeviceIncoming = perDeviceIncoming.copy(total = it)) }
        follow(manager.perDeviceOutgoingLastSecond) { copy(perDeviceOutgoing = perDeviceOutgoing.copy(lastSecond = it)) }
        follow(manager.perDeviceOutgoingLastMinute) { copy(perDeviceOutgoing = perDeviceOutgoing.copy(lastMinute = it)) }
        follow(manager.perDeviceOutgoingTotal) { copy(perDeviceOutgoing = perDeviceOutgoing.copy(total = it)) }
        follow(manager.perPeerIncomingLastSecond) { copy(perPeerIncoming = perPeerIncoming.copy(lastSecond = it)) }
        follow(manager.perPeerIncomingLastMinute) { copy(perPeerIncoming = perPeerIncoming.copy(lastMinute = it)) }
        follow(manager.perPeerIncomingTotal) { copy(perPeerIncoming = perPeerIncoming.copy(total = it)) }
        follow(manager.perPeerOutgoingLastSecond) { copy(perPeerOutgoing = perPeerOutgoing.copy(lastSecond = it)) }
        follow(manager.perPeerOutgoingLastMinute) { copy(perPeerOutgoing = perPeerOutgoing.copy(lastMinute = it)) }
        follow(manager.perPeerOutgoingTotal) { copy(perPeerOutgoing = perPeerOutgoing.copy(total = it)) }

        viewModelScope.launch {
            _uiState.map { it.relayGraph }.distinctUntilChanged().collect { series ->
                savedState[KEY_RELAY_SERIES] = series.toSaveable()
            }
        }

        viewModelScope.launch {
            val info = withContext(ioDispatcher) { runCatching { inspectDistribution() }.getOrNull() }
            _uiState.update { it.copy(distributionInfo = info) }
        }
    }

    fun onAction(action: DebugSettingsAction) {
        when (action) {
            DebugSettingsAction.ScreenShown -> startShown()
            DebugSettingsAction.ScreenHidden -> stopShown()
            DebugSettingsAction.BackClicked -> _events.trySend(DebugSettingsEvent.Close)
            // The Root handles these: they need the clipboard and the permission launcher.
            is DebugSettingsAction.CopyCertificateClicked, DebugSettingsAction.WifiAwareEnableClicked -> Unit

            is DebugSettingsAction.VerboseLoggingToggled -> manager.setVerboseLoggingEnabled(action.enabled)
            is DebugSettingsAction.GattServerToggled -> {
                manager.setGattServerEnabled(action.enabled)
                viewModelScope.launch { mesh?.setGattServerRunning(action.enabled) }
            }
            is DebugSettingsAction.GattClientToggled -> {
                manager.setGattClientEnabled(action.enabled)
                viewModelScope.launch { mesh?.setGattClientRunning(action.enabled) }
            }
            // QD9: every slider step saves and logs.
            is DebugSettingsAction.MaxServerConnectionsChanged ->
                manager.setMaxServerConnections(connectionLimitFromSlider(action.value))
            is DebugSettingsAction.MaxClientConnectionsChanged ->
                manager.setMaxClientConnections(connectionLimitFromSlider(action.value))
            is DebugSettingsAction.MaxOverallConnectionsChanged ->
                manager.setMaxConnectionsOverall(connectionLimitFromSlider(action.value))
            is DebugSettingsAction.BleToggled -> manager.setBleEnabled(action.enabled)
            is DebugSettingsAction.WifiAwareEnableResolved ->
                if (action.permitted) {
                    manager.setWifiAwareEnabled(true)
                } else {
                    manager.addDebugMessage(
                        DebugMessage.SystemMessage("Wi‑Fi Aware needs the Nearby devices permission")
                    )
                }
            DebugSettingsAction.WifiAwareDisableClicked -> manager.setWifiAwareEnabled(false)
            is DebugSettingsAction.WifiAwareVerboseToggled -> manager.setWifiAwareVerbose(action.enabled)
            is DebugSettingsAction.PacketRelayToggled -> manager.setPacketRelayEnabled(action.enabled)
            DebugSettingsAction.WifiAwareAnnounceClicked -> diagnostics.sendWifiAwareAnnounce()
            is DebugSettingsAction.SeenPacketCapacityChanged -> manager.setSeenPacketCapacity(action.value.toInt())
            is DebugSettingsAction.GcsMaxBytesChanged -> manager.setGcsMaxBytes(action.value.toInt())
            is DebugSettingsAction.GcsFprChanged -> manager.setGcsFprPercent(action.value.toDouble())
            is DebugSettingsAction.DisconnectClicked -> mesh?.disconnectDevice(action.address)
            is DebugSettingsAction.ConnectClicked -> mesh?.connectToDevice(action.address)
            DebugSettingsAction.ClearConsoleClicked -> manager.clearDebugMessages()
            is DebugSettingsAction.RelayGraphModeSelected -> {
                _uiState.update { it.copy(relayGraphMode = action.mode) }
                savedState[KEY_RELAY_MODE] = action.mode.name
                // Restart the sampler so the new mode is sampled now, as the card's loop restarted.
                if (shownJob != null) startSampler()
            }
        }
    }

    private fun startShown() {
        shownJob?.cancel()
        startSampler()
        shownJob = viewModelScope.launch(meshDispatcher) {
            manager.setDebugSheetVisible(true)
            var first = true
            while (true) {
                // B1: follow the mesh running now; a mesh that appears or is replaced is picked up here.
                val current = diagnostics.openMesh()
                if (first || current !== mesh) {
                    first = false
                    val changed = current !== mesh
                    mesh = current
                    _uiState.update {
                        it.copy(
                            meshAvailable = current != null,
                            localPeerID = current?.myPeerID,
                            localAdapterAddress = current?.localAdapterAddress(),
                            // A read of another mesh no longer applies.
                            latestSnapshot = if (changed) null else it.latestSnapshot,
                        )
                    }
                }
                pollOnce(current, diagnostics, manager)?.let { snapshot ->
                    _uiState.update { it.copy(latestSnapshot = snapshot) }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun startSampler() {
        samplerJob?.cancel()
        samplerJob = viewModelScope.launch(meshDispatcher) {
            while (true) {
                sampler.sample(
                    mode = _uiState.value.relayGraphMode,
                    stats = manager.relayStats.value,
                    perDevice = RelayCounts(
                        manager.perDeviceIncomingLastSecond.value,
                        manager.perDeviceOutgoingLastSecond.value,
                    ),
                    perPeer = RelayCounts(
                        manager.perPeerIncomingLastSecond.value,
                        manager.perPeerOutgoingLastSecond.value,
                    ),
                )
                _uiState.update { it.copy(relayGraph = sampler.series, relayColorSlots = sampler.colorSlots) }
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    private fun stopShown() {
        shownJob?.cancel()
        shownJob = null
        samplerJob?.cancel()
        samplerJob = null
        // Atomic: it runs even when the ViewModel is cleared right after the screen goes.
        viewModelScope.launch(meshDispatcher, start = CoroutineStart.ATOMIC) {
            manager.setDebugSheetVisible(false)
        }
    }

    private fun <T> follow(flow: StateFlow<T>, apply: DebugSettingsUiState.(T) -> DebugSettingsUiState) {
        viewModelScope.launch { flow.collect { value -> _uiState.update { it.apply(value) } } }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 1000L
        const val SAMPLE_INTERVAL_MS = 1000L
        const val RELAY_PALETTE_SIZE = 10
        const val KEY_RELAY_MODE = "debug_relay_graph_mode"
        const val KEY_RELAY_SERIES = "debug_relay_graph_series"
    }
}
