package com.bitchat.android.wifiaware

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareSession
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.mesh.FragmentManager
import com.bitchat.android.mesh.MeshCore
import com.bitchat.android.service.MeshServiceHolder
import com.bitchat.android.service.TransportBridgeService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mockConstruction
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class)
class WifiAwarePermissionRaceTest {
    @Test
    fun `permission revoked after attach is checked before discovery`() = withService { service, callback, session, scheduler ->
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(
            Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION)
        callback.onAttached(session)
        scheduler.runCurrent()
        assertFalse(service.isRunning())
        verify(session, never()).publish(any(), any(), any())
        verify(session).close()
    }

    @Test
    fun `permission revoked inside publish is contained and session released`() = withService { service, callback, session, scheduler ->
        doThrow(SecurityException("synthetic publish revocation")).whenever(session).publish(any(), any(), any())
        callback.onAttached(session)
        scheduler.runCurrent()
        assertFalse(service.isRunning())
        verify(session).close()
        verify(session, never()).subscribe(any(), any(), any())
    }

    @Test
    fun `permission revoked inside subscribe is contained and session released`() = withService { service, callback, session, scheduler ->
        val publisher = mock<PublishDiscoverySession>()
        doAnswer {
            it.getArgument<DiscoverySessionCallback>(1).onPublishStarted(publisher)
            null
        }.whenever(session).publish(any(), any(), any())
        doThrow(SecurityException("synthetic subscribe revocation")).whenever(session).subscribe(any(), any(), any())
        callback.onAttached(session)
        scheduler.runCurrent()
        assertFalse(service.isRunning())
        verify(session).close()
        verify(publisher).close()
    }

    @Test
    fun `attach permission denial does not start mesh work`() = withService(denyAttach = true) { service, _, _, scheduler ->
        scheduler.runCurrent()
        assertFalse(service.isRunning())
    }

    private fun withService(denyAttach: Boolean = false,
        test: (WifiAwareMeshService, AttachCallback, WifiAwareSession, TestCoroutineScheduler) -> Unit) {
        val app = RuntimeEnvironment.getApplication()
        val context = spy(app)
        doReturn(context).whenever(context).applicationContext
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_WIFI_AWARE, true)
        shadowOf(app).grantPermissions(
            Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION)
        val manager = mock<WifiAwareManager> { on { isAvailable } doReturn true }
        doReturn(manager).whenever(context).getSystemService(WifiAwareManager::class.java)
        val enabledField = WifiAwareController::class.java.getDeclaredField("_enabled").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val enabled = enabledField.get(null) as MutableStateFlow<Boolean>
        enabled.value = true
        MeshServiceHolder.attach(mock<BluetoothMeshService> { on { isReusable() } doReturn true })
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(StandardTestDispatcher(scheduler) + SupervisorJob())
        var callback: AttachCallback? = null
        doAnswer {
            callback = it.getArgument(0)
            if (denyAttach) throw SecurityException("synthetic attach revocation")
            null
        }.whenever(manager).attach(any(), any())
        try {
            mockConstruction(EncryptionService::class.java) { crypto, _ ->
                whenever(crypto.getIdentityFingerprint()).thenReturn("1111222233334444")
            }.use {
                mockConstruction(com.bitchat.android.ui.NotificationManager::class.java).use {
                    mockConstruction(MeshCore::class.java) { core, _ ->
                        whenever(core.fragmentManager).thenReturn(FragmentManager())
                    }.use { cores ->
                        val service = WifiAwareMeshService(context)
                        WifiAwareMeshService::class.java.getDeclaredField("serviceScope").apply {
                            isAccessible = true
                            (get(service) as CoroutineScope).cancel()
                            set(service, scope)
                        }
                        service.startServices()
                        checkNotNull(callback) { "Expected a real attach request" }
                        test(service, callback!!, mock(), scheduler)
                        if (denyAttach) verify(cores.constructed().single(), never()).startCore()
                    }
                }
            }
        } finally {
            enabled.value = false
            scope.cancel()
            TransportBridgeService.unregister("WIFI")
            MeshServiceHolder.clear()
        }
    }
}
