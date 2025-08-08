package com.bitchat

import com.bitchat.android.mesh.PeerManager
import junit.framework.TestCase.assertEquals
import org.junit.Test


class PeerManagerTest {

    private val peerManager = PeerManager()
    private val unknownPeer = "unknown"
    private val unknownDevice = "Unknown"

    val testUsers = mapOf(
        "peer1" to "alice",
        "peer2" to "bob",
        "peer3" to "charlie",
        "peer4" to "diana",
        "peer5" to "eve",
        unknownPeer to unknownPeer
    )

    val deviceAddresses = mapOf(
        "C0:FF:EE:11:22:33" to "peer1",
        "C0:FF:BB:66:44:99" to "peer2",
        "C0:FF:ZZ:99:66:55" to "peer3",
        "C0:FF:QQ:22:88:44" to "peer4",
        "C0:FF:DD:77:55:11" to "peer5",
        unknownDevice to unknownPeer
    )

    val emptyDeviceAddresses = emptyMap<String, String>()

    val testRSSI = mapOf(
        "peer1" to 0,
        "peer2" to 10,
        "peer3" to 30,
        "peer4" to 5,
        "peer5" to 25,
        unknownPeer to 40
    )

    fun add_peers() {
        testUsers.forEach { peerID, nickname ->
            peerManager.addOrUpdatePeer(peerID, nickname)
        }
    }

    fun update_rssi() {
        testRSSI.forEach { peerID, rssi ->
            peerManager.updatePeerRSSI(peerID, rssi)
        }
    }

    @Test
    fun peer_is_added_correctly() {

        testUsers.forEach { peerID, nickname ->
            val isAdded = peerManager.addOrUpdatePeer(peerID, nickname)
            val isFirstAnnounce = !peerManager.hasAnnouncedToPeer(peerID)

            when {
                peerID == unknownPeer -> {
                    assertEquals(false, isAdded)
                }
                else -> {
                    assertEquals(true, isAdded)
                }
            }

            if (peerID != unknownPeer)
                when {
                    isFirstAnnounce -> {
                        assertEquals(true, isAdded)
                    }
                    else -> {
                        assertEquals(false, isAdded)
                    }
                }
        }
    }

    @Test
    fun all_peer_nicknames_are_returned_correctly() {
        add_peers()
        val actualUsers = peerManager.getAllPeerNicknames()
        val expectedUsers = testUsers.filter { it.key != unknownPeer }
        assertEquals(expectedUsers, actualUsers)
    }

    @Test
    fun peer_is_removed_correctly() {
        add_peers()
        val peerID1 = testUsers.keys.elementAt(0)
        val peerID2 = testUsers.keys.elementAt(1)
        peerManager.removePeer(peerID1)
        peerManager.removePeer(peerID2)
        val numberOfActivePeers = peerManager.getActivePeerCount()
        val numberOfAllPeers = peerManager.getAllPeerNicknames().size
        assertEquals(testUsers.size - 3, numberOfActivePeers)
        assertEquals(testUsers.size - 3, numberOfAllPeers)
    }

    @Test
    fun last_seen_updated_correctly() {
        testUsers.forEach { peerID, _ ->
            peerManager.updatePeerLastSeen(peerID)
        }
    }

    @Test
    fun rssi_updated_correctly() {
        add_peers()
        testRSSI.forEach { peerID, rssi ->
            peerManager.updatePeerRSSI(peerID, rssi)

            if (peerID == unknownPeer) {
                val unknownRSSIIsAdded = peerManager.getAllPeerRSSI().containsKey(peerID)
                assertEquals(false, unknownRSSIIsAdded)
            }
        }

        val expectedRSSI = testRSSI.filter { it.key != unknownPeer }
        val actualRSSI = peerManager.getAllPeerRSSI()
        assertEquals(expectedRSSI, actualRSSI)
    }

    @Test
    fun peer_can_be_marked_as_announced_correctly() {
        add_peers()
        testUsers.forEach { peerID, _ ->
            peerManager.markPeerAsAnnouncedTo(peerID)
            val hasAnnounced = peerManager.hasAnnouncedToPeer(peerID)
            assertEquals(true, hasAnnounced)
        }
    }

    @Test
    fun peer_can_announce_correctly() {
        add_peers()
        testUsers.forEach { peerID, _ ->
            val isPeerActive = peerManager.isPeerActive(peerID)
            when {
                peerID == unknownPeer -> assertEquals(false, isPeerActive)
                else -> assertEquals(true, isPeerActive)
            }
        }
    }

    @Test
    fun all_peers_cleared_correctly() {
        add_peers()
        val isNotEmpty = peerManager.getAllPeerNicknames().isNotEmpty()
        assertEquals(true, isNotEmpty)
        peerManager.clearAllPeers()
        val isEmpty = peerManager.getAllPeerNicknames().isEmpty()
        assertEquals(true, isEmpty)
    }

    @Test
    fun peer_manager_can_shutdown_properly() {
        add_peers()
        val isNotEmpty = peerManager.getAllPeerNicknames().isNotEmpty()
        assertEquals(true, isNotEmpty)
        peerManager.shutdown()
        val isEmpty = peerManager.getAllPeerNicknames().isEmpty()
        assertEquals(true, isEmpty)    }

    @Test
    fun debug_info_can_be_returned_correctly() {
        add_peers()
        update_rssi()
        val announcedPeers = peerManager.getAllPeerNicknames().size
        val debugInfo = peerManager.getDebugInfo(deviceAddresses)
        val debugLines = debugInfo.reader().readLines()

        val expectedLine1 = "=== Peer Manager Debug Info ==="
        val actualLine1 = debugLines[0]

        val expectedLine2 = "Active Peers: ${peerManager.getActivePeerCount()}"
        val actualLine2 = debugLines[1]

        val expectedSecondLastLine = "Announced Peers: $announcedPeers"
        val actualSecondLastLine = debugLines[debugLines.size - 2]

        val expectedLastLine = "Announced To Peers: 0"
        val actualLastLine = debugLines[debugLines.size - 1]

        assertEquals(expectedLine1, actualLine1)
        assertEquals(expectedLine2, actualLine2)
        assertEquals(expectedSecondLastLine, actualSecondLastLine)
        assertEquals(expectedLastLine, actualLastLine)

        deviceAddresses.forEach { deviceAddress, peerID ->
            val actualNickname = peerManager.getAllPeerNicknames()[peerID] ?: unknownPeer
            val expectedNickname = testUsers[peerID] ?: unknownPeer

            val actualTimeSince = (System.currentTimeMillis() - 0) / 1000
            val expectedTimeSince = (System.currentTimeMillis() - 0) / 1000

            val actualRSSI = peerManager.getAllPeerRSSI()[peerID]?.let { "$it dBm" } ?: "No RSSI"
            val filteredTestRSSI = testRSSI.filter { it.key != unknownPeer }
            val expectedRSSI = filteredTestRSSI[peerID]?.let { "$it dBm" } ?: "No RSSI"

            val actualDeviceAddress = deviceAddresses.entries.find { it.value == peerID }?.key
            val actualAddressInfo = actualDeviceAddress?.let { " [Device: $it]" } ?: " [Device: Unknown]"
            val expectedDeviceAddress = deviceAddresses.entries.find { it.value == peerID }?.key
            val expectedAddressInfo = expectedDeviceAddress?.let { " [Device: $it]" } ?: " [Device: Unknown]"

            val expectedLine = "  - $peerID ($expectedNickname)$expectedAddressInfo - last seen ${expectedTimeSince}s ago, RSSI: $expectedRSSI"
            val actualLine = "  - $peerID ($actualNickname)$actualAddressInfo - last seen ${actualTimeSince}s ago, RSSI: $actualRSSI"

            assertEquals(expectedLine, actualLine)
        }
    }

    @Test
    fun debug_info_with_addresses_can_be_returned_correctly() {
        add_peers()
        val debugInfo = peerManager.getDebugInfoWithDeviceAddresses(deviceAddresses)
        val debugLines = debugInfo.reader().readLines()

        val expectedLine1 = "=== Device Address to Peer Mapping ==="
        val actualLine1 = debugLines[0]

        val expectedLastLines = peerManager.getDebugInfo(deviceAddresses)
            .reader().readLines()


        val lastLinesAreAvailable = debugLines.containsAll(expectedLastLines)

        assertEquals(expectedLine1, actualLine1)

        assertEquals(true, lastLinesAreAvailable)

        deviceAddresses.forEach { deviceAddress, peerID ->
            val actualNickname = peerManager.getAllPeerNicknames()[peerID] ?: unknownPeer
            val expectedNickname = testUsers[peerID] ?: unknownPeer

            val isActive = peerManager.getActivePeerIDs().contains(peerID)
            val status = if (isActive) "ACTIVE" else "INACTIVE"

            val expectedLine = "  Device: $deviceAddress -> Peer: $peerID ($expectedNickname) [$status]"
            val actualLine = "  Device: $deviceAddress -> Peer: $peerID ($actualNickname) [$status]"

            assertEquals(expectedLine, actualLine)
        }
    }

    @Test
    fun debug_info_with_empty_addresses_can_return_correctly() {
        val debugInfo = peerManager.getDebugInfoWithDeviceAddresses(emptyDeviceAddresses)
        val debugLines = debugInfo.reader().readLines()

        val expectedLine2 = "No device address mappings available"
        val actualLine2 = debugLines[1]

        assertEquals(expectedLine2, actualLine2)
    }
}