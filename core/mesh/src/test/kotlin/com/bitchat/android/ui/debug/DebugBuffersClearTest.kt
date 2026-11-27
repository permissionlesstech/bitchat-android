package com.bitchat.android.ui.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugBuffersClearTest {
    private val settings = DebugSettingsManager(DebugPreferenceManager())

    @Test
    fun `clearing the buffers empties the messages, scan results, devices and Wi-Fi Aware peers`() {
        // Scan results are kept only while the debug sheet is open.
        settings.setDebugSheetVisible(true)
        settings.addDebugMessage(DebugMessage.SystemMessage("synthetic peer-a joined"))
        settings.addScanResult(DebugScanResult("synthetic-name", "00:00:00:00:00:01", -40, "peer-a"))
        settings.updateConnectedDevices(
            listOf(ConnectedDevice("00:00:00:00:00:01", "peer-a", "synthetic-name", -40, ConnectionType.GATT_SERVER, true)),
        )
        settings.updateWifiAwareDiscovered(mapOf("peer-a" to "synthetic-name"))
        settings.updateWifiAwareConnected(mapOf("peer-a" to "192.0.2.1"))
        assertEquals(1, settings.debugMessages.value.size)
        assertEquals(1, settings.scanResults.value.size)

        settings.clearBuffers()

        assertTrue(settings.debugMessages.value.isEmpty())
        assertTrue(settings.scanResults.value.isEmpty())
        assertTrue(settings.connectedDevices.value.isEmpty())
        assertTrue(settings.wifiAwareDiscovered.value.isEmpty())
        assertTrue(settings.wifiAwareConnected.value.isEmpty())
    }

    @Test
    fun `clearing the buffers drops the per-peer and per-device statistics`() {
        val packet = com.bitchat.android.protocol.BitchatPacket(
            version = 1u,
            type = com.bitchat.android.protocol.MessageType.MESSAGE.value,
            senderID = ByteArray(8),
            recipientID = null,
            timestamp = 0uL,
            payload = ByteArray(0),
            ttl = 1u,
            route = null,
        )
        settings.setDebugSheetVisible(true)
        settings.logIncoming(packet, "peer-a", "synthetic-name", "00:00:00:00:00:01", "peer-self")
        settings.logOutgoing("MESSAGE", "peer-a", "synthetic-name", "00:00:00:00:00:01")
        assertTrue(settings.perPeerIncomingTotal.value.containsKey("peer-a"))
        assertTrue(settings.perPeerOutgoingTotal.value.containsKey("peer-a"))
        assertTrue(settings.perPeerIncomingLastSecond.value.containsKey("peer-a"))

        settings.clearBuffers()

        listOf(
            settings.perDeviceLastSecond, settings.perPeerLastSecond,
            settings.perDeviceIncomingLastSecond, settings.perDeviceOutgoingLastSecond,
            settings.perPeerIncomingLastSecond, settings.perPeerOutgoingLastSecond,
            settings.perDeviceIncomingLastMinute, settings.perDeviceOutgoingLastMinute,
            settings.perPeerIncomingLastMinute, settings.perPeerOutgoingLastMinute,
        ).forEach { assertTrue(it.value.isEmpty()) }
        listOf(
            settings.perDeviceIncomingTotal, settings.perDeviceOutgoingTotal,
            settings.perPeerIncomingTotal, settings.perPeerOutgoingTotal,
        ).forEach { assertTrue(it.value.isEmpty()) }

        // The private maps are empty too: a later pass publishes nothing for the old peer.
        settings.logOutgoing("MESSAGE", "peer-b", null, null)
        assertEquals(setOf("peer-b"), settings.perPeerOutgoingTotal.value.keys)
        assertEquals(setOf("peer-b"), settings.perPeerOutgoingLastMinute.value.keys)
        assertTrue(settings.perPeerIncomingTotal.value.isEmpty())
        assertTrue(settings.perPeerIncomingLastMinute.value.isEmpty())
    }
}
