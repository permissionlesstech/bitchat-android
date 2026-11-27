package com.bitchat.android.net

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dagger.Lazy
import info.guardianproject.arti.ArtiLogListener
import info.guardianproject.arti.ArtiProxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ArtiTorManagerDormantRobolectricTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val torPreferences = TorPreferenceManager()

    @Before
    fun setUp() {
        torPreferences.set(application, TorMode.ON)
    }

    @After
    fun tearDown() {
        torPreferences.set(application, TorMode.ON)
    }

    private fun TestScope.manager(
        proxy: ArtiProxy,
        isBackground: Flow<Boolean>,
        onListener: (ArtiLogListener) -> Unit,
    ): ArtiTorManager {
        lateinit var okHttp: OkHttpProvider
        val manager = ArtiTorManager(
            okHttp = Lazy { okHttp },
            torPreferences = torPreferences,
            appScope = backgroundScope,
            proxyFactory = { _, _, listener ->
                onListener(listener)
                proxy
            },
            backgroundState = isBackground,
            backgroundDormantDelayMs = 15_000L,
        )
        okHttp = OkHttpProvider(manager)
        return manager
    }

    @Test
    fun `backgrounding after bootstrap transitions Arti to soft dormant and foregrounding wakes it`() = runTest {
        val isBackground = MutableStateFlow(false)
        val dormantCalls = mutableListOf<Boolean>()
        var capturedListener: ArtiLogListener? = null
        val proxy = mock<ArtiProxy>()
        doAnswer { inv ->
            dormantCalls += inv.getArgument<Boolean>(0)
            Unit
        }.whenever(proxy).setDormant(any())

        val manager = manager(proxy, isBackground) { capturedListener = it }
        manager.init(application)
        advanceTimeBy(10_000L)
        runCurrent()

        // Complete bootstrap
        capturedListener?.onLogLine("Sufficiently bootstrapped; system SOCKS now functional")
        capturedListener?.onLogLine("We have found that guard [scrubbed] is usable.")
        runCurrent()
        assertTrue(manager.isProxyEnabled())
        assertEquals(emptyList<Boolean>(), dormantCalls)

        // Move app to background: should wait debounce delay before entering soft-dormant
        isBackground.value = true
        runCurrent()
        advanceTimeBy(14_999L)
        runCurrent()
        assertEquals(emptyList<Boolean>(), dormantCalls)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(listOf(true), dormantCalls)
        // SOCKS proxy remains advertised and enabled so any new stream wakes Arti cleanly.
        assertTrue(manager.isProxyEnabled())

        // Return to foreground: wakes immediately
        isBackground.value = false
        runCurrent()
        assertEquals(listOf(true, false), dormantCalls)
    }

    @Test
    fun `brief background trip cancelled before delay never toggles dormant mode`() = runTest {
        val isBackground = MutableStateFlow(false)
        val dormantCalls = mutableListOf<Boolean>()
        var capturedListener: ArtiLogListener? = null
        val proxy = mock<ArtiProxy>()
        doAnswer { inv ->
            dormantCalls += inv.getArgument<Boolean>(0)
            Unit
        }.whenever(proxy).setDormant(any())

        val manager = manager(proxy, isBackground) { capturedListener = it }
        manager.init(application)
        advanceTimeBy(10_000L)
        runCurrent()
        capturedListener?.onLogLine("We have found that guard [scrubbed] is usable.")
        runCurrent()

        isBackground.value = true
        advanceTimeBy(5_000L)
        runCurrent()
        isBackground.value = false
        advanceTimeBy(30_000L)
        runCurrent()

        assertEquals(emptyList<Boolean>(), dormantCalls)
    }

    @Test
    fun `bootstrapping while already backgrounded enters soft dormant once guard is usable`() = runTest {
        val isBackground = MutableStateFlow(true)
        val dormantCalls = mutableListOf<Boolean>()
        var capturedListener: ArtiLogListener? = null
        val proxy = mock<ArtiProxy>()
        doAnswer { inv ->
            dormantCalls += inv.getArgument<Boolean>(0)
            Unit
        }.whenever(proxy).setDormant(any())

        val manager = manager(proxy, isBackground) { capturedListener = it }
        manager.init(application)
        advanceTimeBy(10_000L)
        runCurrent()

        // Not bootstrapped yet -> must stay Normal while bootstrapping
        advanceTimeBy(20_000L)
        runCurrent()
        assertEquals(emptyList<Boolean>(), dormantCalls)

        // Bootstrap completes while still backgrounded -> enters soft-dormant after delay
        capturedListener?.onLogLine("We have found that guard [scrubbed] is usable.")
        advanceTimeBy(15_000L)
        runCurrent()
        assertEquals(listOf(true), dormantCalls)
    }

    @Test
    fun `foreground wake racing with background timer always leaves Arti in normal mode`() = runTest {
        val isBackground = MutableStateFlow(false)
        val dormantCalls = mutableListOf<Boolean>()
        var capturedListener: ArtiLogListener? = null
        val proxy = mock<ArtiProxy>()
        doAnswer { inv ->
            dormantCalls += inv.getArgument<Boolean>(0)
            Unit
        }.whenever(proxy).setDormant(any())

        val manager = manager(proxy, isBackground) { capturedListener = it }
        manager.init(application)
        advanceTimeBy(10_000L)
        runCurrent()
        capturedListener?.onLogLine("We have found that guard [scrubbed] is usable.")
        runCurrent()

        // Background until exact timer boundary, then foreground immediately
        isBackground.value = true
        runCurrent()
        advanceTimeBy(15_000L)
        isBackground.value = false
        runCurrent()

        // Final dormant state must be Normal (either never entered Soft, or ended with false)
        assertTrue(dormantCalls.isEmpty() || !dormantCalls.last())
    }

    @Test
    fun `applying TorMode OFF while post-bootstrap waiter finishes never republishes SOCKS address`() = runTest {
        val isBackground = MutableStateFlow(false)
        var capturedListener: ArtiLogListener? = null
        val proxy = mock<ArtiProxy>()

        val manager = manager(proxy, isBackground) { capturedListener = it }
        manager.init(application)
        advanceTimeBy(10_000L)
        runCurrent()

        // Complete bootstrap (wakes post-bootstrap reset coroutine), then immediately turn Tor OFF
        capturedListener?.onLogLine("We have found that guard [scrubbed] is usable.")
        manager.applyMode(application, TorMode.OFF)
        advanceTimeBy(10_000L)
        runCurrent()

        assertEquals(TorMode.OFF, manager.statusFlow.value.mode)
        assertEquals(ArtiTorManager.TorState.OFF, manager.statusFlow.value.state)
        assertNull("OFF must keep socksAddr null even when bootstrap waiter runs concurrently", manager.currentSocksAddress())
        assertFalse(manager.isProxyEnabled())
    }
}
