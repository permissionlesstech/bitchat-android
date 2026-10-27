package com.bitchat.android.wifiaware

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.aware.WifiAwareManager
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.features.voice.LiveVoiceManager
import com.bitchat.android.mesh.MessageHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class WifiAwarePanicStartupRobolectricTest {
    private fun probe(freshBeforeOldCompletes: Boolean, publishedStop: Boolean = false, overlappingStop: Boolean = false) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val ctx = mock(Context::class.java)
        val pm = mock(PackageManager::class.java)
        val aware = mock(WifiAwareManager::class.java)
        val location = mock(LocationManager::class.java)
        `when`(ctx.applicationContext).thenReturn(ctx)
        `when`(ctx.packageManager).thenReturn(pm)
        `when`(pm.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)).thenReturn(true)
        `when`(ctx.getSystemService(WifiAwareManager::class.java)).thenReturn(aware)
        `when`(aware.isAvailable).thenReturn(true)
        `when`(ctx.getSystemService(Context.LOCATION_SERVICE)).thenReturn(location)
        `when`(location.isLocationEnabled).thenReturn(true)
        fun field(name: String) = WifiAwareController::class.java.getDeclaredField(name).apply { isAccessible = true }
        val contextField = field("appContext")
        val priorContext = contextField.get(null)
        @Suppress("UNCHECKED_CAST")
        val enabled = field("_enabled").get(null) as MutableStateFlow<Boolean>
        val priorEnabled = enabled.value
        val scopeField = field("scope")
        val priorScope = scopeField.get(null)
        val scheduler = TestScope()
        val inFlight = field("restartInFlight").get(null) as AtomicBoolean
        val requested = field("restartRequested").get(null) as AtomicBoolean
        val priorInFlight = inFlight.getAndSet(false)
        val priorRequested = requested.getAndSet(false)
        scopeField.set(null, scheduler.backgroundScope)
        var registeredService: WifiAwareMeshService? = null
        val manager = LiveVoiceManager.getInstance(app)
        var oldHandler: MessageHandler? = null
        var freshHandler: MessageHandler? = null
        try {
            WifiAwareController.stop()
            contextField.set(null, ctx)
            enabled.value = true
            mockConstruction(WifiAwareMeshService::class.java) { svc, _ ->
                val firstService = oldHandler == null
                doAnswer {
                    if (firstService && publishedStop) {
                        WifiAwareController.startIfPossible()
                        if (overlappingStop) {
                            // The outer stop has unpublished this service but still owns its cleanup.
                            // A second stop sees no service and finishes before the outer cleanup.
                            WifiAwareController.stop()
                            scheduler.runCurrent()
                            assertNull("An overlapping stop must not release startup before the outer cleanup", WifiAwareController.getService())
                            assertNull("No successor may be constructed while the outer cleanup is outstanding", freshHandler)
                        }
                    }
                    registeredService = null
                    null
                }.`when`(svc).stopServices()
                if (firstService) {
                    oldHandler = MessageHandler("1111222233334444", app)
                    doAnswer {
                        registeredService = svc
                        if (!publishedStop) {
                            manager.suspendForPanic()
                            WifiAwareController.stop()
                            manager.resumeAfterPanic()
                            if (freshBeforeOldCompletes) WifiAwareController.startIfPossible()
                        }
                        null
                    }.`when`(svc).startServices()
                } else {
                    freshHandler = MessageHandler("1111222233334444", app)
                    doAnswer { registeredService = svc; null }.`when`(svc).startServices()
                }
                `when`(svc.isRunning()).thenReturn(true)
            }.use { construction ->
                WifiAwareController.startIfPossible()
                if (publishedStop) {
                    assertSame(construction.constructed().single(), WifiAwareController.getService())
                    manager.suspendForPanic()
                    WifiAwareController.stop()
                    manager.resumeAfterPanic()
                }
                assertEquals(1, construction.constructed().size)
                assertNull(WifiAwareController.getService())
                verify(construction.constructed().first()).stopServices()
                scheduler.runCurrent()
                assertEquals(2, construction.constructed().size)
                assertSame(construction.constructed().last(), WifiAwareController.getService())
                assertTrue(WifiAwareController.running.value)
                assertSame(construction.constructed().last(), registeredService)
                val generation = MessageHandler::class.java.getDeclaredField("liveVoiceReceiverGeneration").apply { isAccessible = true }
                assertNotEquals(manager.currentReceiverGeneration(), generation.getLong(oldHandler))
                assertEquals(manager.currentReceiverGeneration(), generation.getLong(freshHandler))
            }
        } finally {
            enabled.value = false
            WifiAwareController.stop()
            contextField.set(null, priorContext)
            enabled.value = priorEnabled
            oldHandler?.shutdown()
            freshHandler?.shutdown()
            manager.reset()
            scheduler.backgroundScope.cancel()
            scheduler.runCurrent()
            inFlight.set(priorInFlight)
            requested.set(priorRequested)
            scopeField.set(null, priorScope)
        }
    }
    @Test fun stoppedStartupCannotPublishAndFreshStartupUsesCurrentGeneration() = probe(false)
    @Test fun freshStartupWaitsForStaleRegistrationCleanup() = probe(true)
    @Test fun freshStartupWaitsForPublishedServiceCleanup() = probe(false, true)
    @Test fun freshStartupWaitsForBothOverlappingStops() = probe(false, true, true)
    private fun blockedStartCanRetry(reason: String) {
        val ctx = mock(Context::class.java)
        val pm = mock(PackageManager::class.java)
        val aware = mock(WifiAwareManager::class.java)
        val location = mock(LocationManager::class.java)
        `when`(ctx.applicationContext).thenReturn(ctx)
        `when`(ctx.packageManager).thenReturn(pm)
        `when`(ctx.getSystemService(WifiAwareManager::class.java)).thenReturn(aware)
        `when`(ctx.getSystemService(Context.LOCATION_SERVICE)).thenReturn(location)
        fun field(name: String) = WifiAwareController::class.java.getDeclaredField(name).apply { isAccessible = true }
        val contextField = field("appContext")
        val priorContext = contextField.get(null)
        @Suppress("UNCHECKED_CAST")
        val enabled = field("_enabled").get(null) as MutableStateFlow<Boolean>
        val priorEnabled = enabled.value
        val scopeField = field("scope")
        val priorScope = scopeField.get(null)
        val scheduler = TestScope()
        scopeField.set(null, scheduler.backgroundScope)
        fun allowStart() {
            contextField.set(null, ctx)
            enabled.value = true
            `when`(pm.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)).thenReturn(true)
            `when`(aware.isAvailable).thenReturn(true)
            doReturn(true).`when`(location).isLocationEnabled
            `when`(ctx.checkPermission(anyString(), anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED)
        }
        try {
            WifiAwareController.stop()
            allowStart()
            when (reason) {
                "context" -> contextField.set(null, null)
                "unsupported" -> `when`(pm.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)).thenReturn(false)
                "unavailable" -> `when`(aware.isAvailable).thenReturn(false)
                "location-exception" -> doThrow(IllegalStateException("synthetic location failure")).`when`(location).isLocationEnabled
                "location" -> doReturn(false).`when`(location).isLocationEnabled
                "permission" -> `when`(ctx.checkPermission(anyString(), anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_DENIED)
                "disabled" -> doAnswer { enabled.value = false; true }.`when`(location).isLocationEnabled
            }
            mockConstruction(WifiAwareMeshService::class.java) { svc, construction ->
                `when`(svc.isRunning()).thenReturn(true)
                if (reason == "exception" && construction.count == 1) {
                    doThrow(IllegalStateException("synthetic startup failure")).`when`(svc).startServices()
                }
            }.use { construction ->
                try { WifiAwareController.startIfPossible() } catch (e: IllegalStateException) { if (reason != "location-exception") throw e }
                assertNull(WifiAwareController.getService())
                assertFalse(WifiAwareController.running.value)
                assertFalse("blocked startup must release ownership", field("starting").getBoolean(null))
                assertEquals(if (reason == "exception") 1 else 0, construction.constructed().size)
                allowStart()
                WifiAwareController.startIfPossible()
                assertEquals(if (reason == "exception") 2 else 1, construction.constructed().size)
                assertSame(construction.constructed().last(), WifiAwareController.getService())
                assertTrue(WifiAwareController.running.value)
                verify(construction.constructed().last()).startServices()
            }
        } finally {
            enabled.value = false
            WifiAwareController.stop()
            scheduler.backgroundScope.cancel()
            scheduler.runCurrent()
            contextField.set(null, priorContext)
            enabled.value = priorEnabled
            scopeField.set(null, priorScope)
        }
    }

    @Test fun throwingLocationCheckReleasesStartupForRetry() = blockedStartCanRetry("location-exception")

    @Test fun missingContextReleasesStartupForRetry() = blockedStartCanRetry("context")
    @Test fun unsupportedAwareReleasesStartupForRetry() = blockedStartCanRetry("unsupported")
    @Test fun unavailableAwareReleasesStartupForRetry() = blockedStartCanRetry("unavailable")
    @Test fun disabledLocationReleasesStartupForRetry() = blockedStartCanRetry("location")
    @Test @Config(sdk = [33])
    fun deniedNearbyPermissionReleasesStartupForRetry() = blockedStartCanRetry("permission")
    @Test fun disabledDuringReadinessCheckReleasesStartupForRetry() = blockedStartCanRetry("disabled")
    @Test fun throwingStartupReleasesStartupForRetry() = blockedStartCanRetry("exception")

}
