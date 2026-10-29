package com.bitchat.android.ui.debug

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.bitchat.android.services.meshgraph.MeshGraphService
import com.bitchat.android.settings.MeshDiagnostics
import com.bitchat.android.settings.MeshSnapshot
import com.bitchat.android.settings.TransportDiagnostics
import com.bitchat.android.settings.WifiAwareStatus
import com.bitchat.android.spam.SpamFilter
import com.bitchat.android.spam.SpamFilterPreferences
import com.bitchat.android.util.DistributionInfoProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins DebugSettingsViewModel (inventory DA1-DA24, DE1-DE3) against the real
 * manager singleton, reset after each test, and a fake mesh. The poll runs on
 * a test dispatcher, driven by virtual time; every ViewModel is cleared at the
 * end, so no poll outlives its test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DebugSettingsViewModelTest {

    private val peerA = "0123456789abcdef"
    private val peerB = "fedcba9876543210"
    private val address = "00:11:22:33:44:55"

    private val scheduler = TestCoroutineScheduler()
    private val main = UnconfinedTestDispatcher(scheduler)
    private val background = StandardTestDispatcher(scheduler)
    private val store = ViewModelStore()
    private val manager = DebugSettingsManager.getInstance()

    private val graphState = MutableStateFlow(MeshGraphService.GraphSnapshot(emptyList(), emptyList()))
    private val graph = object : MeshGraphSource {
        override val graphState: StateFlow<MeshGraphService.GraphSnapshot> = this@DebugSettingsViewModelTest.graphState
    }

    private val snapshot = MeshSnapshot.assemble(
        entries = listOf(Triple(address, true, -60)),
        addressToPeer = mapOf(address to peerA),
        nicknames = mapOf(peerA to "alice"),
        directPeers = setOf(peerA),
    )

    private class FakeMesh(var snapshot: MeshSnapshot) : MeshDiagnostics {
        var snapshotCalls = 0
        val calls = mutableListOf<String>()
        override val myPeerID: String = "0123456789abcdef"
        override fun snapshot(): MeshSnapshot {
            snapshotCalls++
            return snapshot
        }
        override fun setGattServerRunning(on: Boolean) { calls += "server=$on" }
        override fun setGattClientRunning(on: Boolean) { calls += "client=$on" }
        override fun localAdapterAddress(): String? = "00:11:22:33:44:55"
        override fun connectToDevice(address: String) { calls += "connect=$address" }
        override fun disconnectDevice(address: String) { calls += "disconnect=$address" }
    }

    private val mesh = FakeMesh(snapshot)

    private val wifiAwareStatus = MutableStateFlow(
        WifiAwareStatus(supported = true, available = true, running = false, unsupportedReason = null)
    )

    private inner class FakeDiagnostics(var opens: MeshDiagnostics?) : TransportDiagnostics {
        var openCalls = 0
        var announceCalls = 0
        override val wifiAwareStatus: StateFlow<WifiAwareStatus> = this@DebugSettingsViewModelTest.wifiAwareStatus
        override val wifiAwareConnectedPeers: StateFlow<Map<String, String>> = MutableStateFlow(mapOf(peerB to "192.0.2.1"))
        override val wifiAwareDiscoveredPeers: StateFlow<Map<String, String>> = MutableStateFlow(mapOf(peerB to "alice"))
        override fun sendWifiAwareAnnounce() { announceCalls++ }
        override fun wifiAwarePermissions(): List<String> = listOf("android.permission.NEARBY_WIFI_DEVICES")
        override fun openMesh(): MeshDiagnostics? {
            openCalls++
            return opens
        }
    }

    private val distribution = DistributionInfoProvider.DistributionInfo(
        installSource = "Sideloaded",
        installerPackage = null,
        packageFormat = "Standalone APK",
        architecture = "Universal",
        sharingSource = "Current installed APK",
        versionName = "0.0.0",
        versionCode = 1,
        signingChannel = "Debug",
        certificateSha256 = null,
    )

    private fun newViewModel(
        diagnostics: TransportDiagnostics = FakeDiagnostics(mesh),
        savedState: SavedStateHandle = SavedStateHandle(),
    ): DebugSettingsViewModel {
        val vm = DebugSettingsViewModel(
            manager = manager,
            savedState = savedState,
            graph = graph,
            diagnostics = diagnostics,
            inspectDistribution = { distribution },
            defaultDispatcher = background,
            ioDispatcher = background,
            spamFilterPreferences = SpamFilterPreferences,
            spamFilter = SpamFilter.shared,
        )
        // Held in a store so tearDown can clear it, which cancels its poll.
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = vm as T
        }
        return ViewModelProvider(store, factory)[DebugSettingsViewModel::class.java]
    }

    private fun shown(vm: DebugSettingsViewModel) {
        vm.onAction(DebugSettingsAction.ScreenShown)
        scheduler.runCurrent()
    }

    private fun systemMessages(text: String): Int =
        manager.debugMessages.value.count { it is DebugMessage.SystemMessage && it.content == "⚙️ $text" }

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
        manager.clearDebugMessages()
    }

    @After
    fun tearDown() {
        store.clear()
        manager.setDebugSheetVisible(false)
        manager.setVerboseLoggingEnabled(false)
        manager.setGattServerEnabled(true)
        manager.setGattClientEnabled(true)
        manager.setPacketRelayEnabled(true)
        manager.setBleEnabled(true)
        manager.setWifiAwareEnabled(false)
        manager.setWifiAwareVerbose(false)
        manager.setMaxConnectionsOverall(8)
        manager.setMaxServerConnections(8)
        manager.setMaxClientConnections(8)
        manager.setSeenPacketCapacity(500)
        manager.setGcsMaxBytes(400)
        manager.setGcsFprPercent(1.0)
        manager.updateConnectedDevices(emptyList())
        manager.updateWifiAwareDiscovered(emptyMap())
        manager.updateWifiAwareConnected(emptyMap())
        manager.updateRelayStats(PacketRelayStats())
        manager.clearDebugMessages()
        Dispatchers.resetMain()
    }

    // DE1, DE3

    @Test
    fun screenShown_setsVisibleAndStartsPoll() {
        val vm = newViewModel()

        shown(vm)
        assertTrue(manager.debugSheetVisible.value)
        assertEquals(1, mesh.snapshotCalls)

        scheduler.advanceTimeBy(1000)
        scheduler.runCurrent()
        assertEquals(2, mesh.snapshotCalls)
        assertEquals(snapshot.devices, manager.connectedDevices.value)
        assertEquals(snapshot.devices, vm.uiState.value.connectedDevices)
        assertEquals(snapshot, vm.uiState.value.latestSnapshot)
        assertEquals(mapOf(peerB to "alice"), vm.uiState.value.wifiAwareDiscovered)
        assertEquals(mapOf(peerB to "192.0.2.1"), vm.uiState.value.wifiAwareConnected)

        vm.onAction(DebugSettingsAction.ScreenHidden)
    }

    @Test
    fun screenHidden_clearsVisibleAndStopsPoll() {
        val vm = newViewModel()
        shown(vm)

        vm.onAction(DebugSettingsAction.ScreenHidden)
        scheduler.runCurrent()
        scheduler.advanceTimeBy(5000)
        scheduler.runCurrent()

        assertFalse(manager.debugSheetVisible.value)
        assertEquals(1, mesh.snapshotCalls)
    }

    // DS4: a rotation hides then shows the screen; Debug ends visible and the mesh is opened again.
    @Test
    fun hiddenThenShown_endsVisible_andReopensMesh() {
        val diagnostics = FakeDiagnostics(mesh)
        val vm = newViewModel(diagnostics)
        shown(vm)

        vm.onAction(DebugSettingsAction.ScreenHidden)
        vm.onAction(DebugSettingsAction.ScreenShown)
        scheduler.runCurrent()

        assertTrue(manager.debugSheetVisible.value)
        assertEquals(2, diagnostics.openCalls)
        vm.onAction(DebugSettingsAction.ScreenHidden)
    }

    @Test
    fun screenShown_showsTheOpenedMesh() {
        val vm = newViewModel()

        shown(vm)

        val state = vm.uiState.value
        assertTrue(state.meshAvailable)
        assertEquals(peerA, state.localPeerID)
        assertEquals(address, state.localAdapterAddress)
        vm.onAction(DebugSettingsAction.ScreenHidden)
    }

    // B2: the relay graph samples once a second the whole time Debug is shown; no card needs to be on screen.
    @Test
    fun relaySampler_runsWhileShown_regardlessOfCardVisibility() {
        val vm = newViewModel()
        shown(vm)

        manager.updateRelayStats(PacketRelayStats(lastSecondIncoming = 3, lastSecondOutgoing = 4))
        scheduler.advanceTimeBy(1000)
        scheduler.runCurrent()
        assertEquals(listOf(0f, 3f), vm.uiState.value.relayGraph.overallIncoming.takeLast(2))
        assertEquals(listOf(0f, 4f), vm.uiState.value.relayGraph.overallOutgoing.takeLast(2))

        scheduler.advanceTimeBy(2000)
        scheduler.runCurrent()
        assertEquals(listOf(0f, 3f, 3f, 3f), vm.uiState.value.relayGraph.overallIncoming.takeLast(4))
        assertEquals(RelayGraphSampler.WINDOW, vm.uiState.value.relayGraph.overallIncoming.size)

        vm.onAction(DebugSettingsAction.ScreenHidden)
        scheduler.advanceTimeBy(5000)
        scheduler.runCurrent()
        assertEquals(listOf(0f, 3f, 3f, 3f), vm.uiState.value.relayGraph.overallIncoming.takeLast(4))
    }

    // B2: choosing a mode samples it at once, then once a second, as the card's loop did.
    @Test
    fun relayGraphModeSelected_samplesThatModeNow() {
        val vm = newViewModel()
        shown(vm)
        manager.updateRelayStats(PacketRelayStats(lastSecondIncoming = 5))

        vm.onAction(DebugSettingsAction.RelayGraphModeSelected(GraphMode.PER_PEER))
        scheduler.runCurrent()
        assertEquals(GraphMode.PER_PEER, vm.uiState.value.relayGraphMode)
        // The overall series froze when the mode left it (QD6).
        assertEquals(0f, vm.uiState.value.relayGraph.overallIncoming.last())

        vm.onAction(DebugSettingsAction.RelayGraphModeSelected(GraphMode.OVERALL))
        scheduler.runCurrent()
        assertEquals(5f, vm.uiState.value.relayGraph.overallIncoming.last())
        vm.onAction(DebugSettingsAction.ScreenHidden)
    }

    // B2 (QD17 removed): the colours live with the sampler in the ViewModel, so a rotation keeps them.
    @Test
    fun relayColours_stayAcrossHideAndShow() {
        val vm = newViewModel()
        shown(vm)
        manager.logOutgoing("MESSAGE", toPeerID = peerB, toNickname = null, toDeviceAddress = null)
        manager.logOutgoing("MESSAGE", toPeerID = peerA, toNickname = null, toDeviceAddress = null)
        vm.onAction(DebugSettingsAction.RelayGraphModeSelected(GraphMode.PER_PEER))
        scheduler.runCurrent()
        val colours = vm.uiState.value.relayColorSlots
        assertEquals(mapOf(peerA to 0, peerB to 1), colours.filterKeys { it == peerA || it == peerB })

        vm.onAction(DebugSettingsAction.ScreenHidden)
        vm.onAction(DebugSettingsAction.ScreenShown)
        scheduler.runCurrent()

        assertEquals(colours, vm.uiState.value.relayColorSlots)
        vm.onAction(DebugSettingsAction.ScreenHidden)
    }

    // The series and the mode outlive the process, as the card's saveable state did.
    @Test
    fun relayGraph_restoredFromSavedState() {
        val savedState = SavedStateHandle()
        val first = newViewModel(savedState = savedState)
        shown(first)
        manager.updateRelayStats(PacketRelayStats(lastSecondIncoming = 2))
        scheduler.advanceTimeBy(1000)
        scheduler.runCurrent()
        first.onAction(DebugSettingsAction.RelayGraphModeSelected(GraphMode.PER_DEVICE))
        scheduler.runCurrent()
        first.onAction(DebugSettingsAction.ScreenHidden)
        scheduler.runCurrent()
        val series = first.uiState.value.relayGraph
        store.clear()

        val restored = newViewModel(savedState = savedState)

        assertEquals(GraphMode.PER_DEVICE, restored.uiState.value.relayGraphMode)
        assertEquals(series, restored.uiState.value.relayGraph)
    }

    // B2 (QD4 removed): the legend's nicknames come from each poll's snapshot.
    @Test
    fun legendNicknames_refreshOnEveryPoll() {
        val vm = newViewModel()
        shown(vm)
        assertEquals(mapOf(peerA to "alice"), vm.uiState.value.latestSnapshot?.nicknames)

        mesh.snapshot = snapshot.copy(nicknames = mapOf(peerA to "alice", peerB to "alice"))
        scheduler.advanceTimeBy(1000)
        scheduler.runCurrent()

        assertEquals(mapOf(peerA to "alice", peerB to "alice"), vm.uiState.value.latestSnapshot?.nicknames)
        vm.onAction(DebugSettingsAction.ScreenHidden)
    }

    // DE2
    @Test
    fun distributionInfo_isInspectedOnTheIoDispatcher() {
        val vm = newViewModel()
        assertNull(vm.uiState.value.distributionInfo)

        scheduler.runCurrent()

        assertEquals(distribution, vm.uiState.value.distributionInfo)
    }

    @Test
    fun state_followsGraphAndWifiAwareStatus() {
        val vm = newViewModel()
        val graphNow = MeshGraphService.GraphSnapshot(listOf(MeshGraphService.GraphNode(peerA, "alice")), emptyList())
        val unsupported = WifiAwareStatus(supported = false, available = false, running = false, unsupportedReason = null)

        graphState.value = graphNow
        wifiAwareStatus.value = unsupported

        assertEquals(graphNow, vm.uiState.value.meshGraph)
        assertEquals(unsupported, vm.uiState.value.wifiAwareStatus)
        assertEquals(mapOf(peerB to "192.0.2.1"), vm.uiState.value.wifiAwareLivePeers)
    }

    // Toggles (DA3, DA4, DA6, DA9, DA10, DA11, DA12, DA13, DA17)

    @Test
    fun verboseLoggingToggled_callsSetter() {
        val vm = newViewModel()
        vm.onAction(DebugSettingsAction.VerboseLoggingToggled(true))
        assertTrue(manager.verboseLoggingEnabled.value)
        assertTrue(vm.uiState.value.verboseLogging)
        assertEquals(1, systemMessages("🔊 Verbose logging enabled"))
    }

    @Test
    fun gattServerToggled_savesAndSetsServerRunning() {
        val vm = newViewModel()
        shown(vm)
        vm.onAction(DebugSettingsAction.GattServerToggled(false))
        assertFalse(manager.gattServerEnabled.value)
        assertEquals(listOf("server=false"), mesh.calls)
        vm.onAction(DebugSettingsAction.ScreenHidden)
    }

    @Test
    fun gattClientToggled_savesAndSetsClientRunning() {
        val vm = newViewModel()
        shown(vm)
        vm.onAction(DebugSettingsAction.GattClientToggled(false))
        assertFalse(manager.gattClientEnabled.value)
        assertEquals(listOf("client=false"), mesh.calls)
        vm.onAction(DebugSettingsAction.ScreenHidden)
    }

    // Review Focus 2. Passes trivially until B1 makes a null mesh reachable.
    @Test
    fun noMesh_gattToggleSavesSettingOnly() {
        val vm = newViewModel(FakeDiagnostics(opens = null))
        shown(vm)

        vm.onAction(DebugSettingsAction.GattServerToggled(false))
        vm.onAction(DebugSettingsAction.GattClientToggled(false))

        assertFalse(manager.gattServerEnabled.value)
        assertFalse(manager.gattClientEnabled.value)
        assertFalse(vm.uiState.value.meshAvailable)
        assertNull(vm.uiState.value.localPeerID)
        vm.onAction(DebugSettingsAction.ScreenHidden)
    }

    // B1: the poll looks the mesh up each time, so one that starts while Debug is open shows up.
    @Test
    fun meshStartsWhileShown_isPickedUpOnNextPoll() {
        val diagnostics = FakeDiagnostics(opens = null)
        val vm = newViewModel(diagnostics)
        shown(vm)
        assertFalse(vm.uiState.value.meshAvailable)

        diagnostics.opens = mesh
        scheduler.advanceTimeBy(1000)
        scheduler.runCurrent()

        val state = vm.uiState.value
        assertTrue(state.meshAvailable)
        assertEquals(peerA, state.localPeerID)
        assertEquals(address, state.localAdapterAddress)
        assertEquals(snapshot, state.latestSnapshot)
        assertEquals(1, mesh.snapshotCalls)
        vm.onAction(DebugSettingsAction.ScreenHidden)
    }

    // B1: after a panic clear the old mesh is gone; Debug shows no mesh and stops calling it.
    @Test
    fun meshGoneWhileShown_showsNoMesh_andGattSavesSettingOnly() {
        val diagnostics = FakeDiagnostics(mesh)
        val vm = newViewModel(diagnostics)
        shown(vm)
        assertTrue(vm.uiState.value.meshAvailable)

        diagnostics.opens = null
        scheduler.advanceTimeBy(1000)
        scheduler.runCurrent()
        vm.onAction(DebugSettingsAction.GattServerToggled(false))
        scheduler.runCurrent()

        val state = vm.uiState.value
        assertFalse(state.meshAvailable)
        assertNull(state.localPeerID)
        assertNull(state.latestSnapshot)
        assertFalse(manager.gattServerEnabled.value)
        assertEquals(1, mesh.snapshotCalls)
        assertTrue(mesh.calls.isEmpty())
        vm.onAction(DebugSettingsAction.ScreenHidden)
    }

    @Test
    fun bleToggled_callsSetter() {
        val vm = newViewModel()
        vm.onAction(DebugSettingsAction.BleToggled(false))
        assertFalse(manager.bleEnabled.value)
        assertFalse(vm.uiState.value.bleEnabled)
    }

    @Test
    fun packetRelayToggled_callsSetter() {
        val vm = newViewModel()
        vm.onAction(DebugSettingsAction.PacketRelayToggled(false))
        assertFalse(manager.packetRelayEnabled.value)
        assertFalse(vm.uiState.value.packetRelayEnabled)
    }

    @Test
    fun wifiAwareVerboseToggled_callsSetter() {
        val vm = newViewModel()
        vm.onAction(DebugSettingsAction.WifiAwareVerboseToggled(true))
        assertTrue(manager.wifiAwareVerbose.value)
        assertTrue(vm.uiState.value.wifiAwareVerbose)
    }

    @Test
    fun wifiAwareEnableResolved_permitted_turnsItOn() {
        val vm = newViewModel()
        vm.onAction(DebugSettingsAction.WifiAwareEnableResolved(permitted = true))
        assertTrue(manager.wifiAwareEnabled.value)
        assertTrue(vm.uiState.value.wifiAwareEnabled)
    }

    @Test
    fun wifiAwareEnableResolved_denied_logsWhyAndStaysOff() {
        val vm = newViewModel()
        vm.onAction(DebugSettingsAction.WifiAwareEnableResolved(permitted = false))
        assertFalse(manager.wifiAwareEnabled.value)
        assertEquals(1, systemMessages("Wi‑Fi Aware needs the Nearby devices permission"))
    }

    @Test
    fun wifiAwareDisableClicked_turnsItOff() {
        val vm = newViewModel()
        manager.setWifiAwareEnabled(true)
        vm.onAction(DebugSettingsAction.WifiAwareDisableClicked)
        assertFalse(manager.wifiAwareEnabled.value)
        assertFalse(vm.uiState.value.wifiAwareEnabled)
    }

    // The Root resolves these; the ViewModel must not act on them.
    @Test
    fun enableClickedAndCopy_leaveTheManagerAlone() {
        val vm = newViewModel()
        vm.onAction(DebugSettingsAction.WifiAwareEnableClicked)
        vm.onAction(DebugSettingsAction.CopyCertificateClicked("00:00"))
        assertFalse(manager.wifiAwareEnabled.value)
    }

    // Sliders (DA5, DA7, DA8, DA19, DA20, DA21): every step saves and logs, repeats included.

    @Test
    fun maxServerSlider_savesAndLogsEveryStep_currentBehaviour_QD9() {
        val vm = newViewModel()
        listOf(2f, 3.4f, 3.9f).forEach { vm.onAction(DebugSettingsAction.MaxServerConnectionsChanged(it)) }
        assertEquals(3, manager.maxServerConnections.value)
        assertEquals(2, systemMessages("🖥️ Max server connections set to 3"))
        vm.onAction(DebugSettingsAction.MaxServerConnectionsChanged(0.5f))
        assertEquals(1, vm.uiState.value.maxServerConnections)
    }

    @Test
    fun maxClientSlider_savesAndLogsEveryStep_currentBehaviour_QD9() {
        val vm = newViewModel()
        listOf(2f, 3.4f, 3.9f).forEach { vm.onAction(DebugSettingsAction.MaxClientConnectionsChanged(it)) }
        assertEquals(3, manager.maxClientConnections.value)
        assertEquals(2, systemMessages("📱 Max client connections set to 3"))
        assertEquals(3, vm.uiState.value.maxClientConnections)
    }

    @Test
    fun maxOverallSlider_savesAndLogsEveryStep_currentBehaviour_QD9() {
        val vm = newViewModel()
        listOf(2f, 3.4f, 3.9f).forEach { vm.onAction(DebugSettingsAction.MaxOverallConnectionsChanged(it)) }
        assertEquals(3, manager.maxConnectionsOverall.value)
        assertEquals(2, systemMessages("🔢 Max overall connections set to 3"))
        assertEquals(3, vm.uiState.value.maxConnectionsOverall)
    }

    @Test
    fun seenCapacitySlider_savesAndLogsEveryStep_currentBehaviour_QD9() {
        val vm = newViewModel()
        listOf(100f, 110.5f, 110.9f).forEach { vm.onAction(DebugSettingsAction.SeenPacketCapacityChanged(it)) }
        assertEquals(110, manager.seenPacketCapacity.value)
        assertEquals(2, systemMessages("🧩 max packets per sync set to 110"))
        assertEquals(110, vm.uiState.value.seenPacketCapacity)
    }

    @Test
    fun gcsMaxBytesSlider_savesAndLogsEveryStep_currentBehaviour_QD9() {
        val vm = newViewModel()
        listOf(256f, 300.2f, 300.7f).forEach { vm.onAction(DebugSettingsAction.GcsMaxBytesChanged(it)) }
        assertEquals(300, manager.gcsMaxBytes.value)
        assertEquals(2, systemMessages("🌸 max GCS filter size set to 300 bytes"))
        assertEquals(300, vm.uiState.value.gcsMaxBytes)
    }

    @Test
    fun gcsFprSlider_savesAndLogsEveryStep_currentBehaviour_QD9() {
        val vm = newViewModel()
        listOf(2f, 2.5f, 2.5f).forEach { vm.onAction(DebugSettingsAction.GcsFprChanged(it)) }
        assertEquals(2.5, manager.gcsFprPercent.value, 1e-6)
        assertEquals(2, systemMessages("🎯 GCS FPR set to ${String.format("%.2f", 2.5)}%"))
        assertEquals(2.5, vm.uiState.value.gcsFprPercent, 1e-6)
    }

    // DA18, DA22, DA23, DA24, DA1

    @Test
    fun announceClicked_sendsAnnounce() {
        val diagnostics = FakeDiagnostics(mesh)
        val vm = newViewModel(diagnostics)
        vm.onAction(DebugSettingsAction.WifiAwareAnnounceClicked)
        assertEquals(1, diagnostics.announceCalls)
    }

    @Test
    fun connectAndDisconnect_goToTheOpenedMesh() {
        val vm = newViewModel()
        shown(vm)
        vm.onAction(DebugSettingsAction.ConnectClicked(address))
        vm.onAction(DebugSettingsAction.DisconnectClicked(address))
        assertEquals(listOf("connect=$address", "disconnect=$address"), mesh.calls)
        vm.onAction(DebugSettingsAction.ScreenHidden)
    }

    @Test
    fun clearConsoleClicked_clearsAndLogsIt() {
        val vm = newViewModel()
        manager.addDebugMessage(DebugMessage.SystemMessage("before"))
        vm.onAction(DebugSettingsAction.ClearConsoleClicked)
        assertEquals(listOf("⚙️ 🗑️ Debug messages cleared"), vm.uiState.value.debugMessages.map { it.content })
    }

    @Test
    fun backClicked_emitsClose() {
        val vm = newViewModel()
        val received = mutableListOf<DebugSettingsEvent>()
        val collector = CoroutineScope(main)
        collector.launch { vm.events.collect { received += it } }

        vm.onAction(DebugSettingsAction.BackClicked)

        assertEquals(listOf<DebugSettingsEvent>(DebugSettingsEvent.Close), received)
        collector.cancel()
    }
}
