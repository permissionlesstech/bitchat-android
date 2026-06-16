package com.bitchat.android.services

import com.bitchat.android.model.BitchatMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Date

class AppStateStoreTest {
    @Before
    fun setUp() {
        AppStateStore.clear()
    }

    @After
    fun tearDown() {
        AppStateStore.clear()
    }

    @Test
    fun `public timeline collapses request sync replay even when android message ids differ`() {
        val timestamp = Date(1_700_000_000_000L)
        val originalDelivery = BitchatMessage(
            id = "random-id-from-first-delivery",
            sender = "alice",
            content = "hello from sync",
            timestamp = timestamp,
            senderPeerID = "1122334455667788"
        )
        val requestSyncReplay = originalDelivery.copy(id = "different-random-id-from-replay")

        AppStateStore.addPublicMessage(originalDelivery)
        AppStateStore.addPublicMessage(requestSyncReplay)

        assertEquals(listOf(originalDelivery), AppStateStore.publicMessages.value)
    }

    @Test
    fun `public timeline still keeps same content sent at different packet timestamps`() {
        val first = BitchatMessage(
            id = "first-packet-id",
            sender = "alice",
            content = "same text",
            timestamp = Date(1_700_000_000_000L),
            senderPeerID = "1122334455667788"
        )
        val second = first.copy(
            id = "second-packet-id",
            timestamp = Date(first.timestamp.time + 1_000L)
        )

        AppStateStore.addPublicMessage(first)
        AppStateStore.addPublicMessage(second)

        assertEquals(listOf(first, second), AppStateStore.publicMessages.value)
    }

    @Test
    fun `peer list merges transport updates instead of overwriting`() {
        AppStateStore.setTransportPeers("WIFI", listOf("wifi-peer"))
        AppStateStore.setTransportPeers("BLE", emptyList())

        assertEquals(listOf("wifi-peer"), AppStateStore.peers.value)

        AppStateStore.setTransportPeers("BLE", listOf("ble-peer"))

        assertEquals(listOf("wifi-peer", "ble-peer"), AppStateStore.peers.value)
    }

    @Test
    fun `direct peers union across transports`() {
        AppStateStore.setTransportDirectPeers("BLE", listOf("ble-1", "shared"))
        AppStateStore.setTransportDirectPeers("WIFI", listOf("wifi-1", "shared"))

        assertEquals(
            setOf("ble-1", "wifi-1", "shared"),
            AppStateStore.getDirectPeers()
        )
    }

    @Test
    fun `clearing one transport keeps the other transport direct peers`() {
        AppStateStore.setTransportDirectPeers("BLE", listOf("ble-1"))
        AppStateStore.setTransportDirectPeers("WIFI", listOf("wifi-1"))

        AppStateStore.clearTransportDirectPeers("WIFI")

        assertEquals(setOf("ble-1"), AppStateStore.getDirectPeers())
    }

    @Test
    fun `latest direct peer set replaces previous set for same transport`() {
        AppStateStore.setTransportDirectPeers("WIFI", listOf("wifi-1", "wifi-2"))
        AppStateStore.setTransportDirectPeers("WIFI", listOf("wifi-3"))

        assertEquals(setOf("wifi-3"), AppStateStore.getDirectPeers())
    }
}
