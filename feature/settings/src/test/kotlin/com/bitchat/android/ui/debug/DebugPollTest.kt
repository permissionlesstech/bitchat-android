package com.bitchat.android.ui.debug

import com.bitchat.android.settings.MeshDiagnostics
import com.bitchat.android.settings.MeshSnapshot
import com.bitchat.android.settings.TransportDiagnostics
import com.bitchat.android.settings.WifiAwareStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * Pins one tick of Debug's 1 Hz poll (inventory DE1): the manager mirrors it
 * writes, and that a failing mesh read no longer escapes (Review Focus 3, QD11).
 */
class DebugPollTest {

    private val peerA = "0123456789abcdef"
    private val peerB = "fedcba9876543210"
    private val address = "00:11:22:33:44:55"

    private val manager = DebugSettingsManager(DebugPreferenceManager())

    private val discovered = MutableStateFlow(mapOf(peerA to "alice"))
    private val connected = MutableStateFlow(mapOf(peerB to "192.0.2.1"))
    private val diagnostics = object : TransportDiagnostics {
        override val wifiAwareStatus: StateFlow<WifiAwareStatus> =
            MutableStateFlow(WifiAwareStatus(supported = true, available = true, running = true, unsupportedReason = null))
        override val wifiAwareConnectedPeers: StateFlow<Map<String, String>> = connected
        override val wifiAwareDiscoveredPeers: StateFlow<Map<String, String>> = discovered
        override fun sendWifiAwareAnnounce() = Unit
        override fun wifiAwarePermissions(): List<String> = emptyList()
        override fun openMesh(): MeshDiagnostics? = null
    }

    private class FakeMesh(var read: () -> MeshSnapshot) : MeshDiagnostics {
        override val myPeerID: String = "0123456789abcdef"
        override fun snapshot(): MeshSnapshot = read()
        override fun setGattServerRunning(on: Boolean) = Unit
        override fun setGattClientRunning(on: Boolean) = Unit
        override fun localAdapterAddress(): String? = null
        override fun connectToDevice(address: String) = Unit
        override fun disconnectDevice(address: String) = Unit
    }

    private val earlierDevice =
        ConnectedDevice(address, peerB, null, null, ConnectionType.GATT_SERVER, isDirectConnection = false)

    private val snapshot = MeshSnapshot.assemble(
        entries = listOf(Triple(address, true, -60)),
        addressToPeer = mapOf(address to peerA),
        nicknames = mapOf(peerA to "alice"),
        directPeers = setOf(peerA),
    )

    @Before
    fun setUp() = resetMirrors()

    @After
    fun tearDown() = resetMirrors()

    private fun resetMirrors() {
        manager.updateConnectedDevices(emptyList())
        manager.updateWifiAwareDiscovered(emptyMap())
        manager.updateWifiAwareConnected(emptyMap())
    }

    @Test
    fun poll_writesDevicesAndWifiAwareMirrors_andReturnsTheSnapshot() {
        val result = pollOnce(FakeMesh { snapshot }, diagnostics, manager)

        assertSame(snapshot, result)
        assertEquals(snapshot.devices, manager.connectedDevices.value)
        assertEquals(mapOf(peerA to "alice"), manager.wifiAwareDiscovered.value)
        assertEquals(mapOf(peerB to "192.0.2.1"), manager.wifiAwareConnected.value)
    }

    @Test
    fun snapshotThrows_pollContinues() {
        manager.updateConnectedDevices(listOf(earlierDevice))
        val mesh = FakeMesh { throw IllegalStateException("mesh read failed") }

        val failed = pollOnce(mesh, diagnostics, manager)

        // The failure is caught; the devices keep their last value and Wi-Fi Aware still updates.
        assertNull(failed)
        assertEquals(listOf(earlierDevice), manager.connectedDevices.value)
        assertEquals(mapOf(peerA to "alice"), manager.wifiAwareDiscovered.value)

        // The next tick reads again.
        mesh.read = { snapshot }
        assertSame(snapshot, pollOnce(mesh, diagnostics, manager))
        assertEquals(snapshot.devices, manager.connectedDevices.value)
    }

    @Test
    fun noMesh_clearsConnectedDevicesMirror_stillMirrorsWifiAware() {
        manager.updateConnectedDevices(listOf(earlierDevice))

        assertNull(pollOnce(null, diagnostics, manager))

        assertEquals(emptyList<ConnectedDevice>(), manager.connectedDevices.value)
        assertEquals(mapOf(peerB to "192.0.2.1"), manager.wifiAwareConnected.value)
    }
}
