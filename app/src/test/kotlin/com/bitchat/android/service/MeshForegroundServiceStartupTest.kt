package com.bitchat.android.service

import android.Manifest
import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationManagerCompat
import com.bitchat.android.R
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.mesh.UnifiedMeshService
import javax.inject.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The service promotes itself before it starts any transport, and stops instead of crashing or
 * running in the background when the system refuses it (upstream's test, on this branch's
 * injected service: the fields Hilt fills in onCreate are set by hand, and onCreate is not run).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], application = Application::class)
class MeshForegroundServiceStartupTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val preferences = MeshServicePreferences()
    private val holder = testMeshServiceHolder()

    @Before
    fun setUp() {
        preferences.init(context)
        preferences.setBackgroundEnabled(true)
    }

    @Test
    fun `background start rejection is deferred instead of crashing caller`() {
        val caller = mock<Context>()
        whenever(caller.checkPermission(any(), any(), any())).thenReturn(PackageManager.PERMISSION_GRANTED)
        whenever(caller.startForegroundService(any())).thenThrow(
            ForegroundServiceStartNotAllowedException("synthetic background denial"))
        assertFalse(MeshForegroundService.start(caller, preferences))
        verify(caller).startForegroundService(any())
        verify(caller, never()).startService(any())
    }

    @Test
    fun `permission denial at start is deferred instead of crashing caller`() {
        val caller = mock<Context>()
        whenever(caller.checkPermission(any(), any(), any())).thenReturn(PackageManager.PERMISSION_GRANTED)
        whenever(caller.startForegroundService(any())).thenThrow(SecurityException("synthetic permission revocation"))
        assertFalse(MeshForegroundService.start(caller, preferences))
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
        holder.attach(bluetooth)
        MeshServiceHolder::class.java.getDeclaredField("unifiedMeshService").apply {
            isAccessible = true
            set(holder, unified)
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
        holder.attach(bluetooth)
        val service = attachedService()
        denyPromotion(service, ForegroundServiceStartNotAllowedException("synthetic promotion denial"))
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 7))
        verify(service).stopSelf(7)
        verify(bluetooth, never()).startServices()
        verify(bluetooth, never()).isReusable()
    }

    @Test
    fun `accepted foreground launch promotes before stopping after background mesh is disabled`() {
        val service = attachedService()
        stubNotificationCleanup(service)
        val events = mutableListOf<String>()
        doAnswer { events.add("promote"); null }.whenever(service).startForeground(any(), any(), any())
        doAnswer { events.add("promote"); null }.whenever(service).startForeground(any(), any())
        doAnswer { events.add("stop"); null }.whenever(service).stopSelf(8)

        assertEquals(true, MeshForegroundService.start(context, preferences))
        val request = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        preferences.setBackgroundEnabled(false)

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(request, 0, 8))
        assertEquals(listOf("promote", "stop"), events)
        assertEquals(null, holder.meshService)
    }

    @Test
    fun `revoked permissions before dispatch stop without starting transports`() {
        val service = attachedService()
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        stubNotificationCleanup(service)
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 9))
        verify(service).stopSelf(9)
        verify(service, never()).startForeground(any(), any(), any())
        verify(service, never()).startForeground(any(), any())
        assertEquals(null, holder.meshService)
    }

    private fun attachedService(): MeshForegroundService {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN)
        val controller = testWifiAwareController(meshServiceHolder = holder)
        return spy(Robolectric.buildService(MeshForegroundService::class.java).get()).apply {
            // What Hilt injects in onCreate.
            meshServicePreferences = preferences
            shutdownCoordinator = mock()
            meshServiceHolderProvider = Provider { holder }
            wifiAwareControllerProvider = Provider { controller }
            doReturn("Bitchat").whenever(this).getString(R.string.app_name)
            doReturn("Mesh running").whenever(this).getString(R.string.mesh_service_notification_content, 0)
            doReturn("Quit").whenever(this).getString(R.string.notification_action_quit_bitchat)
        }
    }

    private fun stubNotificationCleanup(service: MeshForegroundService) {
        MeshForegroundService::class.java.getDeclaredField("notificationManager").apply {
            isAccessible = true
            set(service, NotificationManagerCompat.from(context))
        }
        MeshForegroundService::class.java.getDeclaredField("peerAvailabilityNotifier").apply {
            isAccessible = true
            set(service, mock<PeerAvailabilityNotifier>())
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
