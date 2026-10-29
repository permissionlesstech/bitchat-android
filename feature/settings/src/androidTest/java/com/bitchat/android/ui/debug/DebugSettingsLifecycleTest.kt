package com.bitchat.android.ui.debug

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.testing.TestLifecycleOwner
import com.bitchat.android.services.meshgraph.MeshGraphService
import com.bitchat.android.settings.MeshDiagnostics
import com.bitchat.android.settings.MeshSnapshot
import com.bitchat.android.settings.TransportDiagnostics
import com.bitchat.android.settings.WifiAwareStatus
import com.bitchat.android.spam.SpamFilter
import com.bitchat.android.spam.SpamFilterPreferences
import com.bitchat.android.ui.theme.BitchatTheme
import com.bitchat.android.util.DistributionInfoProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * B3: Debug's poll, relay sampler and visibility run only while the screen is
 * at least STARTED. The lifecycle is a TestLifecycleOwner; the poll and the
 * sampler run on a test dispatcher, so time is virtual.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DebugSettingsLifecycleTest {
    @get:Rule val compose = createComposeRule()

    private val peerA = "0123456789abcdef"
    private val address = "00:11:22:33:44:55"

    private val scheduler = TestCoroutineScheduler()
    private val background = StandardTestDispatcher(scheduler)
    private val manager = DebugSettingsManager.getInstance()
    private val owner = TestLifecycleOwner(Lifecycle.State.RESUMED)

    private val mesh = object : MeshDiagnostics {
        @Volatile var snapshotCalls = 0
        override val myPeerID: String = peerA
        override fun snapshot(): MeshSnapshot {
            snapshotCalls++
            return MeshSnapshot.assemble(
                entries = listOf(Triple(address, true, -60)),
                addressToPeer = mapOf(address to peerA),
                nicknames = mapOf(peerA to "alice"),
                directPeers = setOf(peerA),
            )
        }
        override fun setGattServerRunning(on: Boolean) = Unit
        override fun setGattClientRunning(on: Boolean) = Unit
        override fun localAdapterAddress(): String? = address
        override fun connectToDevice(address: String) = Unit
        override fun disconnectDevice(address: String) = Unit
    }

    private val diagnostics = object : TransportDiagnostics {
        override val wifiAwareStatus: StateFlow<WifiAwareStatus> =
            MutableStateFlow(WifiAwareStatus(supported = true, available = true, running = false, unsupportedReason = null))
        override val wifiAwareConnectedPeers: StateFlow<Map<String, String>> = MutableStateFlow(emptyMap())
        override val wifiAwareDiscoveredPeers: StateFlow<Map<String, String>> = MutableStateFlow(emptyMap())
        override fun sendWifiAwareAnnounce() = Unit
        override fun wifiAwarePermissions(): List<String> = emptyList()
        override fun openMesh(): MeshDiagnostics = mesh
    }

    private val graph = object : MeshGraphSource {
        override val graphState: StateFlow<MeshGraphService.GraphSnapshot> =
            MutableStateFlow(MeshGraphService.GraphSnapshot(emptyList(), emptyList()))
    }

    private val store = ViewModelStore()

    private val created = DebugSettingsViewModel(
        manager = manager,
        savedState = SavedStateHandle(),
        graph = graph,
        diagnostics = diagnostics,
        inspectDistribution = {
            DistributionInfoProvider.DistributionInfo(
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
        },
        defaultDispatcher = background,
        ioDispatcher = background,
        spamFilterPreferences = SpamFilterPreferences,
        spamFilter = SpamFilter.shared,
    )

    // Held in a store so tearDown can clear it.
    private val viewModel: DebugSettingsViewModel = ViewModelProvider(
        store,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = created as T
        },
    )[DebugSettingsViewModel::class.java]

    private fun showDebug() {
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                BitchatTheme { DebugSettingsRoot(onClose = {}, viewModel = viewModel) }
            }
        }
        settle()
    }

    private fun moveTo(state: Lifecycle.State) {
        owner.currentState = state
        settle()
    }

    /** Lets the effects reach the ViewModel, then runs what they queued on the test dispatcher. */
    private fun settle() {
        compose.waitForIdle()
        scheduler.runCurrent()
        compose.waitForIdle()
    }

    private fun advance(millis: Long) {
        scheduler.advanceTimeBy(millis)
        scheduler.runCurrent()
        compose.waitForIdle()
    }

    @After
    fun tearDown() {
        // Stop the poll and the sampler before the next test.
        owner.currentState = Lifecycle.State.DESTROYED
        scheduler.runCurrent()
        compose.runOnIdle { store.clear() }
        scheduler.runCurrent()
        manager.setDebugSheetVisible(false)
        manager.updateRelayStats(PacketRelayStats())
        manager.updateConnectedDevices(emptyList())
    }

    @Test
    fun stopped_pausesPollSamplerAndVisibility() {
        showDebug()
        assertTrue(manager.debugSheetVisible.value)
        assertEquals(1, mesh.snapshotCalls)

        moveTo(Lifecycle.State.CREATED)
        val series = viewModel.uiState.value.relayGraph
        advance(5_000)

        assertFalse(manager.debugSheetVisible.value)
        assertEquals(1, mesh.snapshotCalls)
        assertEquals(series, viewModel.uiState.value.relayGraph)
    }

    @Test
    fun restarted_resumes() {
        showDebug()
        manager.updateRelayStats(PacketRelayStats(lastSecondIncoming = 3))
        advance(1_000)
        moveTo(Lifecycle.State.CREATED)
        val before = viewModel.uiState.value.relayGraph.overallIncoming
        val callsBefore = mesh.snapshotCalls
        advance(10_000)

        moveTo(Lifecycle.State.RESUMED)

        assertTrue(manager.debugSheetVisible.value)
        assertEquals(callsBefore + 1, mesh.snapshotCalls)
        // The series continues from where it was: one new sample, no zeros for the time away.
        assertEquals(before.drop(1) + 3f, viewModel.uiState.value.relayGraph.overallIncoming)
        advance(1_000)
        assertEquals(callsBefore + 2, mesh.snapshotCalls)
    }
}
