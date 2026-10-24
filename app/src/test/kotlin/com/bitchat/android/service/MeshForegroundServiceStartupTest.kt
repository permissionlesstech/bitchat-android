package com.bitchat.android.service

import android.Manifest
import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.mesh.UnifiedMeshService
import com.bitchat.android.R
import androidx.core.app.NotificationManagerCompat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.mockito.Mockito.mockConstruction
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], application = Application::class)
class MeshForegroundServiceStartupTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        MeshServicePreferences.init(context)
        MeshServicePreferences.setBackgroundEnabled(true)
        MeshServiceHolder.clear()
    }

    @After
    fun tearDown() {
        MeshServiceHolder.clear()
    }

    @Test
    fun `background start rejection is deferred instead of crashing caller`() {
        val caller = mock<Context>()
        whenever(caller.checkPermission(any(), any(), any())).thenReturn(PackageManager.PERMISSION_GRANTED)
        whenever(caller.startForegroundService(any())).thenThrow(
            ForegroundServiceStartNotAllowedException("synthetic background denial"))
        assertFalse(MeshForegroundService.start(caller))
        verify(caller).startForegroundService(any())
        verify(caller, never()).startService(any())
    }

    @Test
    fun `foreground promotion rejection is contained`() {
        val service = attachedService()
        denyPromotion(service, ForegroundServiceStartNotAllowedException("synthetic promotion denial"))
        assertFalse(promote(service))
    }

    @Test
    fun `permission revocation during promotion is contained`() {
        val service = attachedService()
        denyPromotion(service, SecurityException("synthetic permission revocation"))
        assertFalse(promote(service))
    }

    @Test
    fun `promotion happens before mesh startup`() {
        val events = mutableListOf<String>()
        val bluetooth = mock<BluetoothMeshService> { on { isReusable() } doReturn true }
        val unified = mock<UnifiedMeshService>()
        MeshServiceHolder.attach(bluetooth)
        MeshServiceHolder::class.java.getDeclaredField("unifiedMeshService").apply {
            isAccessible = true
            set(null, unified)
        }
        doAnswer { events.add("mesh"); null }.whenever(unified).startServices()
        val service = attachedService()
        doAnswer { events.add("foreground"); null }.whenever(service).startForeground(any(), any(), any())
        doAnswer { events.add("foreground"); null }.whenever(service).startForeground(any(), any())
        service.onStartCommand(Intent(context, MeshForegroundService::class.java).apply {
            action = MeshForegroundService.ACTION_START
        }, 0, 1)
        assertEquals(listOf("foreground", "mesh"), events)
    }

    @Test
    fun `denied promotion stops without mesh startup or sticky retry`() {
        val bluetooth = mock<BluetoothMeshService> { on { isReusable() } doReturn true }
        MeshServiceHolder.attach(bluetooth)
        val service = attachedService()
        denyPromotion(service, ForegroundServiceStartNotAllowedException("synthetic promotion denial"))
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 7))
        verify(service).stopSelf(7)
        verify(bluetooth, never()).startServices()
        verify(bluetooth, never()).isReusable()
    }

    @Test
    fun `revoked permissions before dispatch stop without starting transports`() {
        val service = attachedService()
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        MeshForegroundService::class.java.getDeclaredField("notificationManager").apply {
            isAccessible = true
            set(service, NotificationManagerCompat.from(context))
        }
        MeshForegroundService::class.java.getDeclaredField("peerAvailabilityNotifier").apply {
            isAccessible = true
            set(service, mock<PeerAvailabilityNotifier>())
        }
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 9))
        verify(service).stopSelf(9)
        verify(service, never()).startForeground(any(), any(), any())
        verify(service, never()).startForeground(any(), any())
        assertEquals(null, MeshServiceHolder.meshService)
    }

    @Test
    fun `onCreate does not initialize mesh before promotion`() {
        val bluetooth = mock<BluetoothMeshService> { on { isReusable() } doReturn true }
        MeshServiceHolder.attach(bluetooth)
        val service = attachedService()
        doReturn("Mesh").whenever(service).getString(R.string.mesh_service_channel_name)
        doReturn("Mesh service").whenever(service).getString(R.string.mesh_service_channel_desc)
        mockConstruction(PeerAvailabilityNotifier::class.java).use {
            try {
                service.onCreate()
                verifyNoInteractions(bluetooth)
            } finally {
                service.onDestroy()
            }
        }
    }

    private fun attachedService(): MeshForegroundService {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN)
        return spy(Robolectric.buildService(MeshForegroundService::class.java).get()).apply {
            doReturn("Bitchat").whenever(this).getString(R.string.app_name)
            doReturn("Mesh running").whenever(this).getString(R.string.mesh_service_notification_content, 0)
            doReturn("Quit").whenever(this).getString(R.string.notification_action_quit_bitchat)
        }
    }

    private fun promote(service: MeshForegroundService): Boolean =
        MeshForegroundService::class.java.getDeclaredMethod("startForegroundCompat", Notification::class.java)
            .apply { isAccessible = true }
            .invoke(service, Notification.Builder(context, "synthetic").build()) as Boolean

    private fun denyPromotion(service: MeshForegroundService, error: RuntimeException) {
        doThrow(error).whenever(service).startForeground(any(), any(), any())
        doThrow(error).whenever(service).startForeground(any(), any())
    }
}
