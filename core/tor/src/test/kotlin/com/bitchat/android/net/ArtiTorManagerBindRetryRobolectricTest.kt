package com.bitchat.android.net

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dagger.Lazy
import info.guardianproject.arti.ArtiException
import info.guardianproject.arti.ArtiProxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ArtiTorManagerBindRetryRobolectricTest {

    private companion object {
        const val DEFAULT_PORT = ArtiTorManager.DEFAULT_SOCKS_PORT
        const val MAX_BIND_RETRIES = ArtiTorManager.MAX_RETRY_ATTEMPTS

        // init() applies the saved mode twice and each OFF waits out the stop timeout.
        const val INIT_SETTLE_MS = 2 * ArtiTorManager.STOP_TIMEOUT_MS + 1
    }

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val torPreferences = TorPreferenceManager()
    private val occupiedPorts = mutableSetOf<Int>()
    private val startAttempts = mutableListOf<Int>()
    private var listeningPort: Int? = null

    @Before
    fun startWithTorOff() {
        torPreferences.set(application, TorMode.OFF)
    }

    @After
    fun restoreDefaultTorMode() {
        torPreferences.set(application, TorMode.ON)
    }

    @Test
    fun `bind failure moves the listener onto the advertised SOCKS port`() = runTest {
        occupiedPorts += DEFAULT_PORT

        val manager = managerWithTorOn()

        assertEquals(listOf(DEFAULT_PORT, DEFAULT_PORT + 1), startAttempts)
        assertEquals(DEFAULT_PORT + 1, listeningPort)
        assertEquals(listeningPort, manager.currentSocksAddress()?.port)
    }

    @Test
    fun `tor off and on after a bind failure listens on the advertised default port`() = runTest {
        occupiedPorts += DEFAULT_PORT
        val manager = managerWithTorOn()
        occupiedPorts.clear()

        manager.applyMode(application, TorMode.OFF)
        assertNull(manager.currentSocksAddress())
        manager.applyMode(application, TorMode.ON)

        assertEquals(DEFAULT_PORT, listeningPort)
        assertEquals(DEFAULT_PORT, manager.currentSocksAddress()?.port)
    }

    @Test
    fun `exhausted bind retries stay fail closed after trying each port once`() = runTest {
        val triedPorts = (DEFAULT_PORT..DEFAULT_PORT + MAX_BIND_RETRIES).toList()
        occupiedPorts += triedPorts

        val manager = managerWithTorOn()

        assertEquals(triedPorts, startAttempts)
        assertNull(listeningPort)
        assertEquals(ArtiTorManager.TorState.ERROR, manager.statusFlow.value.state)
        assertFalse(manager.statusFlow.value.running)
        assertFalse(manager.isProxyEnabled())
        // A published SOCKS address keeps HTTP clients on the dead proxy instead of going direct.
        assertNotNull(manager.currentSocksAddress())
    }

    private suspend fun TestScope.managerWithTorOn(): ArtiTorManager {
        lateinit var okHttp: OkHttpProvider
        val manager = ArtiTorManager(
            okHttp = Lazy { okHttp },
            torPreferences = torPreferences,
            appScope = backgroundScope,
            proxyFactory = { _, socksPort, _ -> fakeProxy(socksPort) }
        )
        okHttp = OkHttpProvider(manager)
        manager.init(application)
        advanceTimeBy(INIT_SETTLE_MS)
        runCurrent()
        manager.applyMode(application, TorMode.ON)
        return manager
    }

    /** Behaves like ArtiProxy: the port is fixed at construction and bound by start(). */
    private fun fakeProxy(port: Int): ArtiProxy {
        val proxy = mock<ArtiProxy>()
        doAnswer {
            startAttempts += port
            if (port in occupiedPorts) {
                throw ArtiException("Failed to bind SOCKS proxy to port $port (port already in use)")
            }
            listeningPort = port
            null
        }.whenever(proxy).start()
        doAnswer {
            if (listeningPort == port) listeningPort = null
            null
        }.whenever(proxy).stop()
        return proxy
    }
}
