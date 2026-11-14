package com.bitchat.android.ui.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugBuffersClearTest {
    private val settings = DebugSettingsManager(DebugPreferenceManager())

    @Test
    fun `clearing the buffers empties the messages, scan results, devices and Wi-Fi Aware peers`() {
        settings.addDebugMessage(DebugMessage.SystemMessage("synthetic peer-a joined"))
        settings.addScanResult(DebugScanResult("synthetic-name", "00:00:00:00:00:01", -40, "peer-a"))
        settings.updateConnectedDevices(
            listOf(ConnectedDevice("00:00:00:00:00:01", "peer-a", "synthetic-name", -40, ConnectionType.GATT_SERVER, true)),
        )
        settings.updateWifiAwareDiscovered(mapOf("peer-a" to "synthetic-name"))
        settings.updateWifiAwareConnected(mapOf("peer-a" to "192.0.2.1"))
        assertEquals(1, settings.debugMessages.value.size)

        settings.clearBuffers()

        assertTrue(settings.debugMessages.value.isEmpty())
        assertTrue(settings.scanResults.value.isEmpty())
        assertTrue(settings.connectedDevices.value.isEmpty())
        assertTrue(settings.wifiAwareDiscovered.value.isEmpty())
        assertTrue(settings.wifiAwareConnected.value.isEmpty())
    }
}
