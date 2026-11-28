package com.bitchat.android.net

import android.app.Application
import com.bitchat.android.util.AppConstants
import info.guardianproject.arti.ArtiException
import info.guardianproject.arti.ArtiProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ArtiTorManagerRestartRobolectricTest {

    private val application: Application = mock()

    /** Records proxy calls; the first [failFirstStarts] starts throw a non-bind failure. */
    private class ProxyProbe(private var failFirstStarts: Int = 0) {
        var startCalls = 0
            private set
        var running = false
            private set

        val proxy: ArtiProxy = mock {
            doAnswer {
                startCalls++
                if (failFirstStarts > 0) {
                    failFirstStarts--
                    throw ArtiException("synthetic native failure")
                }
                running = true
                Unit
            }.on { start() }
            doAnswer {
                running = false
                Unit
            }.on { stop() }
        }
    }

    @Test
    fun `failed start is retried and brings tor back up`() = withManager(
        probe = ProxyProbe(failFirstStarts = 1)
    ) { manager, probe ->
        manager.applyMode(application, TorMode.ON)

        advanceUntilIdle()

        assertEquals(2, probe.startCalls)
        assertTrue(probe.running)
        assertEquals(TorMode.ON, manager.statusFlow.value.mode)
        assertTrue(manager.statusFlow.value.running)
    }

    @Test
    fun `silent bootstrap restarts tor instead of leaving it stopped`() = withManager { manager, probe ->
        manager.applyMode(application, TorMode.ON)
        assertEquals(1, probe.startCalls)

        manager.makeBootstrapLookInactive()
        advanceUntilIdle()

        assertEquals(2, probe.startCalls)
        assertTrue(probe.running)
        assertEquals(TorMode.ON, manager.statusFlow.value.mode)
    }

    @Test
    fun `turning tor off during an automatic restart leaves tor stopped`() = withManager { manager, probe ->
        manager.applyMode(application, TorMode.ON)
        manager.makeBootstrapLookInactive()

        // Turn Tor off during the restart's backoff, after the stop wait and before Arti restarts.
        advanceTimeBy(MID_RESTART_BACKOFF_MS)
        runCurrent()
        assertFalse("restart should have stopped the proxy", probe.running)
        val off = launch { manager.applyMode(application, TorMode.OFF) }

        advanceUntilIdle()
        off.join()

        assertFalse(probe.running)
        assertEquals(TorMode.OFF, manager.statusFlow.value.mode)
        assertEquals(ArtiTorManager.TorState.OFF, manager.statusFlow.value.state)
        assertNull(manager.currentSocksAddress())
        assertFalse(manager.isProxyEnabled())
    }

    private fun withManager(
        probe: ProxyProbe = ProxyProbe(),
        body: suspend TestScope.(ArtiTorManager, ProxyProbe) -> Unit
    ) = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        try {
            body(newManager(probe.proxy, scope), probe)
        } finally {
            scope.cancel()
        }
    }

    private fun newManager(proxy: ArtiProxy, scope: CoroutineScope): ArtiTorManager {
        val manager = ArtiTorManager::class.java.getDeclaredConstructor()
            .apply { isAccessible = true }
            .newInstance()
        manager.setField("appScope", scope)
        manager.setField("artiProxy", proxy)
        manager.setField("currentApplication", application)
        return manager
    }

    /** Backdates Arti's last log line, because the watchdog compares it against the wall clock. */
    private fun ArtiTorManager.makeBootstrapLookInactive() {
        val field = ArtiTorManager::class.java.getDeclaredField("lastLogTime")
        field.isAccessible = true
        (field.get(this) as AtomicLong).set(0L)
    }

    private fun ArtiTorManager.setField(name: String, value: Any) {
        val field = ArtiTorManager::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(this, value)
    }

    private companion object {
        const val MID_RESTART_BACKOFF_MS =
            AppConstants.Tor.INACTIVITY_TIMEOUT_MS +
                AppConstants.Tor.STOP_TIMEOUT_MS +
                200L +
                AppConstants.Tor.RESTART_DELAY_MS / 2
    }
}
