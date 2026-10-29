package com.bitchat.android.di

import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.onboarding.DeviceReadiness
import com.bitchat.android.service.MeshServiceHolder
import com.bitchat.android.wifiaware.WifiAwareController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * B1: Debug's `openMesh()` follows the mesh the holder has now and never
 * creates or replaces one, whether or not the current one is reusable.
 */
class AppTransportDiagnosticsTest {

    private val diagnostics = AppTransportDiagnostics(mock<DeviceReadiness>(), WifiAwareController, MeshServiceHolder)

    @Before
    fun setUp() {
        MeshServiceHolder.clear()
    }

    @After
    fun tearDown() {
        MeshServiceHolder.clear()
    }

    @Test
    fun noMeshRunning_openMeshReturnsNull_andCreatesNone() {
        assertNull(diagnostics.openMesh())
        assertNull(MeshServiceHolder.meshService)
    }

    @Test
    fun meshReplaced_nextOpenMeshReturnsNewInstance() {
        // Neither mesh is stubbed reusable: openMesh must not care.
        val first = mock<BluetoothMeshService>()
        val second = mock<BluetoothMeshService>()
        whenever(first.myPeerID).thenReturn("0123456789abcdef")
        whenever(second.myPeerID).thenReturn("fedcba9876543210")

        MeshServiceHolder.attach(first)
        assertEquals("0123456789abcdef", diagnostics.openMesh()?.myPeerID)

        MeshServiceHolder.attach(second)
        assertEquals("fedcba9876543210", diagnostics.openMesh()?.myPeerID)
        assertEquals(second, MeshServiceHolder.meshService)
    }
}
