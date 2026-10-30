package com.bitchat.android.hotspot

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.bitchat.android.share.HotspotRadioLease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Pins HotspotViewModel's logic (inventory B.6, H1–H14) by driving the callback the
 * ViewModel hands to its controller. No radio, socket or Wi-Fi is touched.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HotspotViewModelTest {

    private val apkFile = File("synthetic/bitchat.apk")
    private val info = HotspotManager.ConnectionInfo(
        ssid = "bitchat-test",
        password = "synthetic-pass",
        ipAddress = "192.0.2.1",
        connectedPeers = 0
    )

    private lateinit var lease: FakeLease
    private lateinit var radioLease: FakeRadioLease
    private lateinit var controllers: FakeControllerFactory
    private lateinit var servers: FakeServingFactory

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        lease = FakeLease()
        radioLease = FakeRadioLease { lease }
        controllers = FakeControllerFactory()
        servers = FakeServingFactory()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // H1
    @Test
    fun startFromIntro_setsStartingAcquiresLeaseAndStartsWithoutConfirmedName() {
        val viewModel = newViewModel()
        assertEquals(HotspotUiState.Intro, viewModel.uiState.value)

        start(viewModel)

        assertEquals(HotspotUiState.Starting, viewModel.uiState.value)
        assertEquals(1, radioLease.acquireCount)
        assertEquals(1, controllers.created.size)
        assertEquals(listOf<String?>(null), controllers.last.startNames)
    }

    // H2
    @Test
    fun startWhileStartingOrActive_isIgnored_butAllowedFromError() {
        val viewModel = newViewModel()
        start(viewModel)
        start(viewModel)
        assertEquals(1, controllers.created.size)

        controllers.last.info = info
        controllers.last.callback.onHotspotStarted()
        assertTrue(viewModel.uiState.value is HotspotUiState.Active)
        start(viewModel)
        assertEquals(1, controllers.created.size)

        controllers.last.callback.onError("synthetic failure")
        start(viewModel)
        assertEquals(2, controllers.created.size)
        assertEquals(HotspotUiState.Starting, viewModel.uiState.value)
    }

    // H3
    @Test
    fun hotspotStartedWithInfo_startsServerAndBecomesActive() {
        val viewModel = newViewModel()
        start(viewModel)
        controllers.last.info = info.copy(connectedPeers = 2)

        controllers.last.callback.onHotspotStarted()

        assertEquals(listOf(apkFile), servers.files)
        assertEquals(1, servers.last.startCount)
        assertEquals(
            HotspotUiState.Active(
                ssid = "bitchat-test",
                password = "synthetic-pass",
                ipAddress = "192.0.2.1",
                port = 9999,
                connectedPeers = 2
            ),
            viewModel.uiState.value
        )
    }

    // H4
    @Test
    fun hotspotStartedWithoutInfo_failsAndTearsDown() {
        val viewModel = newViewModel()
        start(viewModel)

        controllers.last.callback.onHotspotStarted()

        assertEquals(
            HotspotUiState.Error("Failed to get hotspot connection info"),
            viewModel.uiState.value
        )
        assertTrue(servers.files.isEmpty())
        assertEquals(1, controllers.last.stopCount)
    }

    // H5
    @Test
    fun webServerThrows_failsWithItsMessageAndTearsDown() {
        servers.failWith = IllegalStateException("synthetic bind failure")
        val viewModel = newViewModel()
        start(viewModel)
        controllers.last.info = info

        controllers.last.callback.onHotspotStarted()

        assertEquals(
            HotspotUiState.Error("Failed to start web server: synthetic bind failure"),
            viewModel.uiState.value
        )
        assertEquals(1, controllers.last.stopCount)
        // The server never got stored, so teardown has nothing to stop.
        assertEquals(0, servers.last.stopCount)
    }

    // H6
    @Test
    fun connectionInfoUpdated_changesOnlyPeers_onlyWhenActiveWithInfo() {
        val viewModel = newViewModel()
        start(viewModel)
        val controller = controllers.last

        controller.callback.onConnectionInfoUpdated(info.copy(connectedPeers = 5))
        assertEquals(HotspotUiState.Starting, viewModel.uiState.value)

        controller.info = info
        controller.callback.onHotspotStarted()
        controller.callback.onConnectionInfoUpdated(
            info.copy(ssid = "other", password = "other", ipAddress = "192.0.2.2", connectedPeers = 3)
        )
        val active = viewModel.uiState.value as HotspotUiState.Active
        assertEquals(3, active.connectedPeers)
        assertEquals("bitchat-test", active.ssid)
        assertEquals("synthetic-pass", active.password)
        assertEquals("192.0.2.1", active.ipAddress)

        controller.callback.onConnectionInfoUpdated(null)
        assertEquals(active, viewModel.uiState.value)
    }

    // H7
    @Test
    fun existingGroupConflict_tearsDownAndAsksToConfirm() {
        val viewModel = newViewModel()
        start(viewModel)

        controllers.last.callback.onExistingGroupConflict("DIRECT-xy-Synthetic")

        assertEquals(HotspotUiState.ConfirmDisconnect, viewModel.uiState.value)
        assertEquals(1, controllers.last.stopCount)
    }

    // H8
    @Test
    fun confirmDisconnect_restartsWithTheConflictingGroupName() {
        val viewModel = newViewModel()
        // Outside the dialog a confirm does nothing.
        confirmDisconnect(viewModel)
        assertTrue(controllers.created.isEmpty())

        start(viewModel)
        controllers.last.callback.onExistingGroupConflict("DIRECT-xy-Synthetic")
        confirmDisconnect(viewModel)

        assertEquals(HotspotUiState.Starting, viewModel.uiState.value)
        assertEquals(2, controllers.created.size)
        assertEquals(listOf<String?>("DIRECT-xy-Synthetic"), controllers.last.startNames)
        assertEquals(2, radioLease.acquireCount)

        // The pending answer was used up: a second confirm does nothing.
        controllers.last.callback.onExistingGroupConflict("DIRECT-zz-Synthetic")
        confirmDisconnect(viewModel)
        confirmDisconnect(viewModel)
        assertEquals(3, controllers.created.size)
    }

    // H9
    @Test
    fun cancelDisconnect_returnsToIntro_andALateCancelIsIgnored() {
        val viewModel = newViewModel()
        start(viewModel)
        controllers.last.callback.onExistingGroupConflict("DIRECT-xy-Synthetic")

        cancelDisconnect(viewModel)
        assertEquals(HotspotUiState.Intro, viewModel.uiState.value)

        start(viewModel)
        controllers.last.callback.onExistingGroupConflict("DIRECT-xy-Synthetic")
        confirmDisconnect(viewModel)
        val restarted = controllers.last
        cancelDisconnect(viewModel)

        assertEquals(HotspotUiState.Starting, viewModel.uiState.value)
        assertEquals(0, restarted.stopCount)
    }

    // H10
    @Test
    fun managerError_failsWithItsMessageAndTearsDown() {
        val viewModel = newViewModel()
        start(viewModel)
        controllers.last.info = info
        controllers.last.callback.onHotspotStarted()

        controllers.last.callback.onError("synthetic group failure")

        assertEquals(
            HotspotUiState.Error("synthetic group failure"),
            viewModel.uiState.value
        )
        assertEquals(1, servers.last.stopCount)
        assertEquals(1, controllers.last.stopCount)
    }

    // H11
    @Test
    fun leaseAcquireThrows_failsWithTheExceptionMessage() {
        radioLease = FakeRadioLease { throw IllegalStateException("synthetic radio busy") }
        val viewModel = newViewModel()

        start(viewModel)

        assertEquals(HotspotUiState.Error("synthetic radio busy"), viewModel.uiState.value)
        assertTrue(controllers.created.isEmpty())
    }

    // H11
    @Test
    fun launchThrowsWithoutMessage_failsWithUnknownError() {
        radioLease = FakeRadioLease { throw IllegalStateException() }
        val viewModel = newViewModel()

        start(viewModel)

        assertEquals(HotspotUiState.Error("Unknown error"), viewModel.uiState.value)
    }

    // H12
    @Test
    fun stop_tearsDownAndReturnsToIntro() {
        val viewModel = newViewModel()
        start(viewModel)
        controllers.last.info = info
        controllers.last.callback.onHotspotStarted()

        stop(viewModel)

        assertEquals(HotspotUiState.Intro, viewModel.uiState.value)
        assertEquals(1, servers.last.stopCount)
        assertEquals(1, controllers.last.stopCount)
    }

    // H12
    @Test
    fun retryFromError_returnsToIntro_andTheNextStartRuns() {
        val viewModel = newViewModel()
        start(viewModel)
        controllers.last.callback.onError("synthetic group failure")

        retry(viewModel)
        assertEquals(HotspotUiState.Intro, viewModel.uiState.value)

        start(viewModel)
        assertEquals(2, controllers.created.size)
    }

    // H13
    @Test
    fun teardownWithoutManager_releasesTheLeaseAtOnce() {
        controllers.failWith = IllegalStateException("synthetic manager failure")
        val viewModel = newViewModel()

        start(viewModel)

        assertEquals(HotspotUiState.Error("synthetic manager failure"), viewModel.uiState.value)
        assertEquals(1, lease.closeCalls)
    }

    // H13
    @Test
    fun teardownWithManager_releasesOnTeardownCompletion_fallbackIsANoOp() {
        val viewModel = newViewModel()
        start(viewModel)

        stop(viewModel)
        assertFalse(lease.released)

        controllers.last.completeTeardown()
        assertTrue(lease.released)
        assertEquals(1, lease.closeCalls)

        // The fallback still fires; the real lease ignores the second close.
        shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.SECONDS)
        assertEquals(2, lease.closeCalls)
        assertEquals(1, lease.releaseCount)
    }

    // H13
    @Test
    fun teardownWithManager_releasesAfterTenSecondsWhenTheFrameworkNeverAnswers() {
        val viewModel = newViewModel()
        start(viewModel)

        stop(viewModel)
        shadowOf(Looper.getMainLooper()).idleFor(9_999, TimeUnit.MILLISECONDS)
        assertFalse(lease.released)

        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS)
        assertTrue(lease.released)
    }

    // H13
    @Test
    fun secondTeardown_isANoOp() {
        val viewModel = newViewModel()
        start(viewModel)
        controllers.last.callback.onError("synthetic group failure")
        val controller = controllers.last

        stop(viewModel)
        stop(viewModel)

        assertEquals(1, controller.stopCount)
        controller.completeTeardown()
        shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.SECONDS)
        assertEquals(2, lease.closeCalls)
        assertEquals(1, lease.releaseCount)
    }

    // H14
    @Test
    fun clearingTheViewModel_stopsTheHotspot() {
        val store = ViewModelStore()
        val viewModel = providerFor(store)[HotspotViewModel::class.java]
        start(viewModel)
        controllers.last.info = info
        controllers.last.callback.onHotspotStarted()

        store.clear()

        assertEquals(1, servers.last.stopCount)
        assertEquals(1, controllers.last.stopCount)
        assertEquals(HotspotUiState.Intro, viewModel.uiState.value)
    }

    // Review Focus 1: a rotation keeps the ViewModel, so the dialog and its pending answer survive.
    @Test
    fun rotationDuringConfirmDisconnect_keepsDialog() {
        val store = ViewModelStore()
        val before = providerFor(store)[HotspotViewModel::class.java]
        start(before)
        controllers.last.callback.onExistingGroupConflict("DIRECT-xy-Synthetic")

        val after = providerFor(store)[HotspotViewModel::class.java]

        assertSame(before, after)
        assertEquals(HotspotUiState.ConfirmDisconnect, after.uiState.value)
        confirmDisconnect(after)
        assertEquals(HotspotUiState.Starting, after.uiState.value)
        assertEquals(listOf<String?>("DIRECT-xy-Synthetic"), controllers.last.startNames)
    }

    // Close leaves through an event; the Activity finishes on it.
    @Test
    fun closeClicked_sendsCloseAndLeavesTheHotspotAlone() = runTest {
        val viewModel = newViewModel()
        start(viewModel)

        viewModel.onAction(HotspotAction.CloseClicked)

        assertEquals(HotspotEvent.Close, viewModel.events.first())
        assertEquals(HotspotUiState.Starting, viewModel.uiState.value)
        assertEquals(0, controllers.last.stopCount)
    }

    // --- the ViewModel's API, in one place ---

    private fun newViewModel() = HotspotViewModel(
        SavedStateHandle(mapOf(HotspotActivity.EXTRA_APK_PATH to apkFile.path)),
        radioLease,
        controllers,
        servers
    )

    private fun providerFor(store: ViewModelStore) = ViewModelProvider(
        store,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = newViewModel() as T
        }
    )

    private fun start(viewModel: HotspotViewModel) = viewModel.onAction(HotspotAction.StartClicked)
    private fun confirmDisconnect(viewModel: HotspotViewModel) =
        viewModel.onAction(HotspotAction.ConfirmDisconnect)
    private fun cancelDisconnect(viewModel: HotspotViewModel) =
        viewModel.onAction(HotspotAction.CancelDisconnect)
    private fun retry(viewModel: HotspotViewModel) = viewModel.onAction(HotspotAction.RetryClicked)
    private fun stop(viewModel: HotspotViewModel) = viewModel.onAction(HotspotAction.StopRequested)

    // --- fakes ---

    /** Idempotent like the real lease: only the first close releases. */
    private class FakeLease : AutoCloseable {
        var closeCalls = 0
        var releaseCount = 0
        val released get() = releaseCount > 0
        override fun close() {
            closeCalls++
            if (releaseCount == 0) releaseCount++
        }
    }

    private class FakeRadioLease(private val onAcquire: () -> AutoCloseable) : HotspotRadioLease {
        var acquireCount = 0
        override fun acquire(): AutoCloseable {
            acquireCount++
            return onAcquire()
        }
    }

    private class FakeController : HotspotController {
        lateinit var callback: HotspotManager.HotspotCallback
        val startNames = mutableListOf<String?>()
        var stopCount = 0
        var info: HotspotManager.ConnectionInfo? = null
        private val teardownCallbacks = mutableListOf<() -> Unit>()

        override fun startHotspot(
            callback: HotspotManager.HotspotCallback,
            confirmedReplacementName: String?
        ) {
            this.callback = callback
            startNames += confirmedReplacementName
        }

        override fun stopHotspot(onTeardownComplete: (() -> Unit)?) {
            stopCount++
            onTeardownComplete?.let(teardownCallbacks::add)
        }

        override fun getConnectionInfo() = info

        fun completeTeardown() {
            teardownCallbacks.toList().forEach { it() }
            teardownCallbacks.clear()
        }
    }

    private class FakeControllerFactory : HotspotControllerFactory {
        val created = mutableListOf<FakeController>()
        var failWith: Exception? = null
        val last get() = created.last()
        override fun create(): HotspotController {
            failWith?.let { throw it }
            return FakeController().also(created::add)
        }
    }

    private class FakeServing(private val failWith: Exception?) : ApkWebServing {
        var startCount = 0
        var stopCount = 0
        override fun startServer() {
            startCount++
            failWith?.let { throw it }
        }
        override fun stopServer() {
            stopCount++
        }
    }

    private class FakeServingFactory : ApkWebServingFactory {
        val files = mutableListOf<File>()
        val servers = mutableListOf<FakeServing>()
        var failWith: Exception? = null
        val last get() = servers.last()
        override fun create(apkFile: File): ApkWebServing {
            files += apkFile
            return FakeServing(failWith).also(servers::add)
        }
    }
}
