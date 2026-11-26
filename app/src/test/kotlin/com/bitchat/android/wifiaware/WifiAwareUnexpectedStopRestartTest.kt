package com.bitchat.android.wifiaware

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.WifiAwareManager
import android.os.Handler
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.mesh.BluetoothMeshService
import org.mockito.kotlin.doReturn
import com.bitchat.android.mesh.FragmentingPacketSender
import com.bitchat.android.mesh.MeshCore
import com.bitchat.android.service.testMeshServiceHolder
import com.bitchat.android.service.testWifiAwareController
import javax.inject.Provider
import kotlinx.coroutines.Dispatchers
import com.bitchat.android.ui.NotificationManager
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.MockedConstruction
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The radio can end an Aware session at any time, and the service also ends its own
 * sessions to refresh discovery. Each of those stops has to come back on its own: the
 * user's setting says Aware is on, and no Activity resume may be coming.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WifiAwareUnexpectedStopRestartTest {

    private val awareManager: WifiAwareManager = mock()
    private lateinit var context: Context
    private val constructionStubs = mutableListOf<MockedConstruction<*>>()
    private val holder = testMeshServiceHolder().apply {
        attach(mock<BluetoothMeshService> { on { isReusable() } doReturn true })
    }
    // What the controller builds on its next start: a real service unless a test stubs one.
    private var newMesh: () -> WifiAwareMeshService = {
        testWifiAwareMesh(context, controller, holder, Dispatchers.IO)
    }
    private val controller: WifiAwareController = testWifiAwareController(
        meshServiceHolder = holder,
        newMesh = Provider { newMesh() },
    )

    @Before
    fun setUp() {
        // Robolectric has no AndroidKeyStore, and these collaborators are not under test.
        constructionStubs += Mockito.mockConstruction(EncryptionService::class.java) { mock, _ ->
            whenever(mock.getIdentityFingerprint()).thenReturn(SYNTHETIC_FINGERPRINT)
        }
        constructionStubs += Mockito.mockConstruction(NotificationManager::class.java)
        constructionStubs += Mockito.mockConstruction(MeshCore::class.java)
        constructionStubs += Mockito.mockConstruction(FragmentingPacketSender::class.java)
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_WIFI_AWARE, true)
        shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(true)
        shadowOf(app).grantPermissions(android.Manifest.permission.NEARBY_WIFI_DEVICES)
        whenever(awareManager.isAvailable).thenReturn(true)
        context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSystemService(name: String): Any? =
                if (name == Context.WIFI_AWARE_SERVICE) awareManager else super.getSystemService(name)
        }
    }

    @After
    fun tearDown() {
        controller.setEnabled(false)
        constructionStubs.forEach { it.close() }
    }

    @Test
    fun `an unexpected session end re-attaches the same service without an outside start`() {
        controller.initialize(context, enabledByDefault = true)
        val service = controller.getService()!!
        assertTrue(controller.running.value)

        val callback = ArgumentCaptor.forClass(AttachCallback::class.java)
        Mockito.verify(awareManager).attach(callback.capture(), any<Handler>())

        callback.value.onAwareSessionTerminated()

        assertFalse(
            "the controller must not report a stopped transport as running",
            controller.running.value
        )
        runBlocking {
            withTimeout(RESTART_TIMEOUT_MS) { controller.running.first { it } }
        }
        Mockito.verify(awareManager, Mockito.times(2)).attach(any<AttachCallback>(), any<Handler>())
        assertSame(
            "recovery reuses the instance rather than orphaning it",
            service,
            controller.getService()
        )
    }

    @Test
    fun `a restart deferred by recovery cleanup keeps the instance for the next attempt`() {
        withStubbedService { constructed, _ ->
            val service = constructed.single()
            controller.onServiceInterrupted(service)

            // The stub stays not-running through startServices(), as a deferred start does.
            controller.startIfPossible()

            assertFalse(controller.running.value)
            assertSame(service, controller.getService())
            assertEquals(1, constructed.size)
            verify(service, never()).stopServices()
        }
    }

    @Test
    fun `a stop racing a reused start is not undone by the start`() {
        withStubbedService { constructed, isRunning ->
            val service = constructed.single()
            controller.onServiceInterrupted(service)
            whenever(service.startServices()).thenAnswer {
                controller.stop()
                isRunning.set(true)
            }

            controller.startIfPossible()

            assertFalse(controller.running.value)
            assertNull(controller.getService())
        }
    }

    @Test
    fun `an interruption from a replaced instance leaves the current one running`() {
        withStubbedService(initiallyRunning = true) { constructed, _ ->
            val current = constructed.single()

            controller.onServiceInterrupted(mock())

            assertTrue(controller.running.value)
            assertSame(current, controller.getService())
        }
    }

    @Test
    fun `a cooldown requested during a restart attempt delays the next attempt`() {
        withStubbedService { constructed, isRunning ->
            val service = constructed.single()
            controller.onServiceInterrupted(service)
            val attemptTimesNs = CopyOnWriteArrayList<Long>()
            whenever(service.startServices()).thenAnswer {
                attemptTimesNs += System.nanoTime()
                if (attemptTimesNs.size == 1) {
                    // An attach failure answered before the attempt finishes publishing.
                    controller.restartIfStillEnabled(COALESCED_COOLDOWN_MS)
                } else {
                    // Ends the loop so it cannot run into the next test.
                    isRunning.set(true)
                }
            }

            controller.restartIfStillEnabled()
            runBlocking {
                withTimeout(RESTART_TIMEOUT_MS) { controller.running.first { it } }
            }

            val gapMs = TimeUnit.NANOSECONDS.toMillis(attemptTimesNs[1] - attemptTimesNs[0])
            assertTrue(
                "next attempt came after ${gapMs}ms, inside the ${COALESCED_COOLDOWN_MS}ms cooldown",
                gapMs >= COALESCED_COOLDOWN_MS
            )
        }
    }

    @Test
    fun `repeated attach failures back off to a capped retry interval`() {
        val delays = (1..8).map(WifiAwareMeshService::attachRetryDelayMs)

        assertEquals(listOf(3_000L, 6_000L, 12_000L, 24_000L, 48_000L, 60_000L, 60_000L, 60_000L), delays)
    }

    private fun withStubbedService(
        initiallyRunning: Boolean = false,
        block: (List<WifiAwareMeshService>, AtomicBoolean) -> Unit
    ) {
        // Starts out running so the controller publishes it, as a healthy first start does.
        val isRunning = AtomicBoolean(true)
        val constructed = CopyOnWriteArrayList<WifiAwareMeshService>()
        newMesh = {
            mock<WifiAwareMeshService>().also {
                whenever(it.isRunning()).thenAnswer { isRunning.get() }
                constructed += it
            }
        }
        controller.initialize(context, enabledByDefault = true)
        isRunning.set(initiallyRunning)
        block(constructed, isRunning)
    }

    private companion object {
        // Longer than the service's restart delay plus one controller retry.
        const val RESTART_TIMEOUT_MS = 20_000L
        // Longer than the controller's fixed retry delay, so ignoring it is detectable.
        const val COALESCED_COOLDOWN_MS = 4_500L
        const val SYNTHETIC_FINGERPRINT = "00112233445566778899aabbccddeeff"
    }
}
