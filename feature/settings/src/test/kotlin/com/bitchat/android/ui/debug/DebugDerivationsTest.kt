package com.bitchat.android.ui.debug

import com.bitchat.android.services.meshgraph.MeshGraphService
import com.bitchat.android.settings.MeshSnapshot
import com.bitchat.android.settings.WifiAwareStatus
import com.bitchat.android.util.DistributionInfoProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins Debug settings' pure decisions (inventory D1-D27). Synthetic peers and addresses only. */
class DebugDerivationsTest {

    private val peerA = "0123456789abcdef"
    private val peerB = "fedcba9876543210"
    private val address = "00:11:22:33:44:55"
    private val otherAddress = "00:11:22:33:44:66"

    private fun device(
        address: String,
        peerID: String?,
        type: ConnectionType = ConnectionType.GATT_SERVER,
    ) = ConnectedDevice(address, peerID, nickname = null, rssi = null, connectionType = type, isDirectConnection = false)

    private fun status(
        supported: Boolean = true,
        available: Boolean = true,
        running: Boolean = false,
        reason: String? = null,
    ) = WifiAwareStatus(supported, available, running, reason)

    // D1
    @Test
    fun snapshot_labelsEachDevice_withPeerNicknameRoleAndDirect() {
        val snapshot = MeshSnapshot.assemble(
            entries = listOf(Triple(address, true, -60), Triple(otherAddress, false, null)),
            addressToPeer = mapOf(address to peerA),
            nicknames = mapOf(peerA to "alice"),
            directPeers = setOf(peerA),
        )

        assertEquals(
            listOf(
                ConnectedDevice(address, peerA, "alice", -60, ConnectionType.GATT_CLIENT, isDirectConnection = true),
                ConnectedDevice(otherAddress, null, null, null, ConnectionType.GATT_SERVER, isDirectConnection = false),
            ),
            snapshot.devices,
        )
        assertEquals(mapOf(address to peerA), snapshot.addressToPeer)
        assertEquals(mapOf(peerA to "alice"), snapshot.nicknames)
    }

    // D1
    @Test
    fun snapshot_peerWithoutNicknameOrDirect_isLabelledWithoutThem() {
        val snapshot = MeshSnapshot.assemble(
            entries = listOf(Triple(address, false, -70)),
            addressToPeer = mapOf(address to peerB),
            nicknames = emptyMap(),
            directPeers = emptySet(),
        )
        assertEquals(
            ConnectedDevice(address, peerB, null, -70, ConnectionType.GATT_SERVER, isDirectConnection = false),
            snapshot.devices.single(),
        )
    }

    // D3, D4
    @Test
    fun blePeersAndCounts_comeFromTheConnectedDevices() {
        val devices = listOf(
            device(address, peerA, ConnectionType.GATT_SERVER),
            device(otherAddress, null, ConnectionType.GATT_CLIENT),
            device("00:11:22:33:44:77", peerA, ConnectionType.GATT_CLIENT),
        )
        assertEquals(setOf(peerA), blePeerIDs(devices))
        assertEquals(ConnectionCounts(server = 1, client = 2, overall = 3), connectionCounts(devices))
    }

    // D5
    @Test
    fun connectionLimitSlider_truncates_andNeverGoesBelowOne() {
        assertEquals(8, connectionLimitFromSlider(8.9f))
        assertEquals(1, connectionLimitFromSlider(0.4f))
        assertEquals(32, connectionLimitFromSlider(32f))
    }

    // D6
    @Test
    fun transportsLabelAndSwitch_unsupportedFirst_thenAvailability() {
        assertEquals("unsupported", wifiAwareTransportLabel(status(supported = false, available = true)))
        assertEquals("available", wifiAwareTransportLabel(status(available = true, running = false)))
        assertEquals("unavailable", wifiAwareTransportLabel(status(available = false, running = true)))

        assertTrue(wifiAwareSwitchChecked(enabled = true, status = status()))
        assertFalse(wifiAwareSwitchChecked(enabled = true, status = status(supported = false)))
        assertFalse(wifiAwareSwitchChecked(enabled = false, status = status()))
    }

    // D7
    @Test
    fun cardStatus_runningBeatsAvailability() {
        assertEquals("unsupported", wifiAwareCardStatus(status(supported = false, running = true)))
        assertEquals("running", wifiAwareCardStatus(status(available = false, running = true)))
        assertEquals("unavailable", wifiAwareCardStatus(status(available = false)))
        assertEquals("stopped", wifiAwareCardStatus(status(available = true)))
    }

    // D8
    @Test
    fun unsupportedText_onlyWhenUnsupported_withADefault() {
        assertNull(wifiAwareUnsupportedText(status(reason = "ignored")))
        assertEquals("No hardware", wifiAwareUnsupportedText(status(supported = false, reason = "No hardware")))
        assertEquals(
            "Wi-Fi Aware is not supported on this device",
            wifiAwareUnsupportedText(status(supported = false)),
        )
    }

    // D10
    @Test
    fun enablingWifiAware_requestsMissing_thenOnlyNearbyDevicesBlocks() {
        val nearby = android.Manifest.permission.NEARBY_WIFI_DEVICES
        val local = "android.permission.ACCESS_LOCAL_NETWORK"
        val required = listOf(nearby, local)

        assertEquals(listOf(local), missingPermissions(required) { it == nearby })
        assertTrue(missingPermissions(required) { true }.isEmpty())

        assertTrue(canEnableWifiAwareAfterRequest(required) { it == nearby })
        assertFalse(canEnableWifiAwareAfterRequest(required) { it == local })
        // Nearby devices not needed on this device: nothing blocks.
        assertTrue(canEnableWifiAwareAfterRequest(listOf(local)) { false })
        assertTrue(canEnableWifiAwareAfterRequest(emptyList()) { false })
    }

    // D11
    @Test
    fun discoveredLines_nicknameOrPrefix_atMostFifty() {
        assertEquals(
            listOf("• alice (01234567…) ", "• fedcba98… (fedcba98…) "),
            wifiAwareDiscoveredLines(linkedMapOf(peerA to "alice", peerB to " ")),
        )
        val many = (0 until 60).associate { "%016x".format(it) to "" }
        assertEquals(50, wifiAwareDiscoveredLines(many).size)
    }

    // D12
    @Test
    fun connectedLines_prefixAndAddress_atMostFifty() {
        assertEquals(listOf("• 01234567… @ 192.0.2.1"), wifiAwareConnectedLines(mapOf(peerA to "192.0.2.1")))
        val many = (0 until 60).associate { "%016x".format(it) to "192.0.2.1" }
        assertEquals(50, wifiAwareConnectedLines(many).size)
    }

    // D13
    @Test
    fun relayHeaders_secondMinuteQuarterHourAndTotal() {
        val stats = PacketRelayStats(
            lastSecondIncoming = 1, lastMinuteIncoming = 2, last15MinuteIncoming = 3, totalIncomingCount = 4,
            lastSecondOutgoing = 5, lastMinuteOutgoing = 6, last15MinuteOutgoing = 7, totalOutgoingCount = 8,
        )
        assertEquals("1/s • 2/m • 3/15m • total 4", relayIncomingHeader(stats))
        assertEquals("5/s • 6/m • 7/15m • total 8", relayOutgoingHeader(stats))
    }

    // D16
    @Test
    fun legendTitle_perPeer_nicknameAndPrefix_orPrefix() {
        assertEquals("alice (012345)", legendTitle(GraphMode.PER_PEER, peerA, mapOf(peerA to "alice"), emptyList(), emptyMap()))
        assertEquals("012345", legendTitle(GraphMode.PER_PEER, peerA, mapOf(peerA to ""), emptyList(), emptyMap()))
        assertEquals("012345", legendTitle(GraphMode.PER_PEER, peerA, mapOf(peerA to null), emptyList(), emptyMap()))
    }

    // D16
    @Test
    fun legendTitle_perDevice_connectedDevicePeerBeatsTheAddressMap() {
        val nicknames = mapOf(peerA to "alice")
        assertEquals(
            "$address (alice (012345))",
            legendTitle(GraphMode.PER_DEVICE, address, nicknames, listOf(device(address, peerA)), mapOf(address to peerB)),
        )
        assertEquals(
            "$address (fedcba)",
            legendTitle(GraphMode.PER_DEVICE, address, nicknames, listOf(device(address, null)), mapOf(address to peerB)),
        )
        assertEquals(address, legendTitle(GraphMode.PER_DEVICE, address, nicknames, emptyList(), emptyMap()))
        assertEquals(address, legendTitle(GraphMode.OVERALL, address, nicknames, emptyList(), emptyMap()))
    }

    // D17
    @Test
    fun legendMetrics_secondMinuteTotal_zeroWhenMissing_emptyForOverall() {
        val second = mapOf(peerA to 1)
        val minute = mapOf(peerA to 12)
        val total = mapOf(peerA to 300L)
        assertEquals("1/s • 12/m • total 300", legendMetrics(GraphMode.PER_PEER, peerA, second, minute, total))
        assertEquals("0/s • 0/m • total 0", legendMetrics(GraphMode.PER_DEVICE, peerB, second, minute, total))
        assertEquals("", legendMetrics(GraphMode.OVERALL, peerA, second, minute, total))
    }

    // D18
    @Test
    fun highlight_togglesOnAndOff_dimsTheOthers() {
        assertEquals(peerA, toggleHighlight(null, peerA))
        assertNull(toggleHighlight(peerA, peerA))
        assertEquals(peerB, toggleHighlight(peerA, peerB))

        assertFalse(isDimmed(null, peerA))
        assertFalse(isDimmed(peerA, peerA))
        assertTrue(isDimmed(peerA, peerB))
    }

    // D21
    @Test
    fun gcsDerived_fromTheDefaults() {
        assertEquals(GcsDerived(p = 7, maxElements = 355), gcsDerived(fprPercent = 1.0, maxBytes = 400))
    }

    // D22
    @Test
    fun console_newestFirst_atMostHundred() {
        val messages = (1..120).map { DebugMessage.PeerEvent("m$it") }
        val lines = consoleLines(messages)
        assertEquals(100, lines.size)
        assertEquals("m120", lines.first())
        assertEquals("m21", lines.last())
    }

    // D23, D24
    @Test
    fun deviceRowTitle_peerOrUnknown_thenAddress() {
        assertEquals("$peerA • $address", deviceRowTitle(peerA, address, "unknown"))
        assertEquals("unknown • $address", deviceRowTitle(null, address, "unknown"))
    }

    // D26
    @Test
    fun topologyLabel_prefixAndNickname_orUnknown() {
        assertEquals("01234567 • alice", topologyLabel(MeshGraphService.GraphNode(peerA, "alice")))
        assertEquals("fedcba98 • Unknown", topologyLabel(MeshGraphService.GraphNode(peerB, null)))
    }

    // D27
    @Test
    fun distributionRows_inOrder_installerOnlyWhenKnown_certificateFallback() {
        val info = DistributionInfoProvider.DistributionInfo(
            installSource = "Sideloaded",
            installerPackage = null,
            packageFormat = "APK",
            architecture = "universal",
            sharingSource = "Installed APK",
            versionName = "1.2.3",
            versionCode = 42,
            signingChannel = "Debug",
            certificateSha256 = null,
        )
        assertEquals(
            listOf(
                "Install source" to "Sideloaded",
                "Package format" to "APK",
                "APK architecture" to "universal",
                "Sharing source" to "Installed APK",
                "Version" to "1.2.3 (42)",
                "Signing channel" to "Debug",
                "Certificate SHA-256" to "Unavailable",
            ),
            distributionRows(info),
        )
        val withInstaller = distributionRows(info.copy(installerPackage = "com.example.store", certificateSha256 = "AB:CD"))
        assertEquals("Installer package" to "com.example.store", withInstaller[1])
        assertEquals("Certificate SHA-256" to "AB:CD", withInstaller.last())
    }
}
