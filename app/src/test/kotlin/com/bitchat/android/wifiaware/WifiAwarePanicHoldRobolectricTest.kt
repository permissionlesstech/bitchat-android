package com.bitchat.android.wifiaware

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.aware.WifiAwareManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class WifiAwarePanicHoldRobolectricTest {
    private fun field(name: String) = WifiAwareController::class.java.getDeclaredField(name)
        .apply { isAccessible = true }

    private fun withController(pendingStart: Boolean = false, action: (TestScope, MutableList<WifiAwareMeshService>, () -> Long) -> Unit) {
        val ctx = mock(Context::class.java)
        val pm = mock(PackageManager::class.java)
        val aware = mock(WifiAwareManager::class.java)
        val location = mock(LocationManager::class.java)
        `when`(ctx.applicationContext).thenReturn(ctx)
        `when`(ctx.packageManager).thenReturn(pm)
        `when`(pm.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)).thenReturn(true)
        `when`(ctx.getSystemService(WifiAwareManager::class.java)).thenReturn(aware)
        `when`(ctx.getSystemService(Context.LOCATION_SERVICE)).thenReturn(location)
        `when`(aware.isAvailable).thenReturn(true)
        `when`(location.isLocationEnabled).thenReturn(true)
        val scheduler = TestScope()
        @Suppress("UNCHECKED_CAST")
        val enabled = field("_enabled").get(null) as MutableStateFlow<Boolean>
        val priorEnabled = enabled.value
        val saved = listOf("appContext", "scope", "panicSuspended", "panicGeneration").associateWith { field(it).get(null) }
        val inFlight = field("restartInFlight").get(null) as AtomicBoolean
        val requested = field("restartRequested").get(null) as AtomicBoolean
        val oldInFlight = inFlight.getAndSet(false)
        val oldRequested = requested.getAndSet(false)
        var pendingGeneration = 0L
        val created = mutableListOf<WifiAwareMeshService>()
        try {
            enabled.value = false
            WifiAwareController.stop()
            field("panicSuspended").set(null, false)
            field("appContext").set(null, ctx)
            field("scope").set(null, scheduler.backgroundScope)
            enabled.value = true
            mockConstruction(WifiAwareMeshService::class.java) { service, _ ->
                created += service
                `when`(service.isRunning()).thenReturn(true)
                if (pendingStart && created.size == 1) {
                    doAnswer {
                        // Panic begins while the old service owns startup and has not published.
                        pendingGeneration = WifiAwareController.suspendForPanic()
                        WifiAwareController.startIfPossible()
                        null
                    }.`when`(service).startServices()
                }
            }.use {
                action(scheduler, created) { pendingGeneration }
            }
        } finally {
            enabled.value = false
            WifiAwareController.stop()
            scheduler.backgroundScope.cancel()
            scheduler.runCurrent()
            saved.forEach { (name, value) -> field(name).set(null, value) }
            enabled.value = priorEnabled
            inFlight.set(oldInFlight)
            requested.set(oldRequested)
        }
    }

    @Test fun pendingStartupIsRejectedAndFreshStartupWaitsForPanicCompletion() = withController(true) { scheduler, created, pending ->
        WifiAwareController.startIfPossible()
        scheduler.runCurrent()
        assertEquals(1, created.size)
        verify(created.single()).stopServices()
        assertNull("Pre-panic startup cannot publish after panic begins", WifiAwareController.getService())
        WifiAwareController.resumeAfterPanic(pending())
        scheduler.runCurrent()
        assertEquals(2, created.size)
        assertSame(created.last(), WifiAwareController.getService())
    }

    @Test fun queuedAndNewRestartRequestsStayHeldUntilSuccessfulCompletion() = withController { scheduler, created, _ ->
        WifiAwareController.restartIfStillEnabled(500)
        val generation = WifiAwareController.suspendForPanic()
        WifiAwareController.startIfPossible()
        WifiAwareController.restartIfStillEnabled()
        scheduler.advanceTimeBy(30001)
        scheduler.runCurrent()
        assertTrue("Queued retry and direct startup stay held", created.isEmpty())
        WifiAwareController.resumeAfterPanic(generation)
        scheduler.runCurrent()
        assertEquals(1, created.size)
        assertSame(created.single(), WifiAwareController.getService())
    }

    @Test fun olderCompletionCannotReleaseNewerPanic() = withController { scheduler, created, _ ->
        val first = WifiAwareController.suspendForPanic()
        val retry = WifiAwareController.suspendForPanic()
        WifiAwareController.resumeAfterPanic(first)
        WifiAwareController.startIfPossible()
        scheduler.runCurrent()
        assertTrue("Older completion must leave newer panic held", created.isEmpty())
        WifiAwareController.resumeAfterPanic(retry)
        scheduler.runCurrent()
        assertEquals(1, created.size)
        WifiAwareController.resumeAfterPanic(retry)
        scheduler.runCurrent()
        assertEquals("Duplicate completion does not replace a healthy service", 1, created.size)
    }

    @Test fun disabledPreferenceSurvivesPanicCompletion() = withController { scheduler, created, _ ->
        val generation = WifiAwareController.suspendForPanic()
        WifiAwareController.setEnabled(false)
        WifiAwareController.resumeAfterPanic(generation)
        scheduler.runCurrent()
        assertFalse(WifiAwareController.enabled.value)
        assertTrue(created.isEmpty())
        WifiAwareController.setEnabled(true)
        assertEquals(1, created.size)
    }

    @Test fun enablingDuringPanicWaitsForCompletion() = withController { scheduler, created, _ ->
        WifiAwareController.setEnabled(false)
        val generation = WifiAwareController.suspendForPanic()
        WifiAwareController.setEnabled(true)
        scheduler.runCurrent()
        assertTrue(WifiAwareController.enabled.value)
        assertTrue(created.isEmpty())
        WifiAwareController.resumeAfterPanic(generation)
        scheduler.runCurrent()
        assertEquals(1, created.size)
    }

    @Test fun hotspotHoldSurvivesPanicCompletion() = withController { scheduler, created, _ ->
        val hotspot = WifiAwareController.acquireHotspotLease()
        try {
            val generation = WifiAwareController.suspendForPanic()
            WifiAwareController.resumeAfterPanic(generation)
            scheduler.runCurrent()
            assertTrue(WifiAwareController.isHeldForHotspot())
            assertTrue(created.isEmpty())
        } finally { hotspot.close() }
        // An already waiting restart loop checks the released hotspot after its 2 s backoff.
        scheduler.advanceTimeBy(2001)
        scheduler.runCurrent()
        assertEquals(1, created.size)
    }

    @Test fun hotspotCompletionDoesNotReleasePanic() = withController { scheduler, created, _ ->
        val generation = WifiAwareController.suspendForPanic()
        WifiAwareController.acquireHotspotLease().close()
        scheduler.runCurrent()
        assertTrue(created.isEmpty())
        WifiAwareController.resumeAfterPanic(generation)
        scheduler.runCurrent()
        assertEquals(1, created.size)
    }
}
