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

    @Test
    fun `private chat aliases merge into canonical peer`() {
        val live = BitchatMessage(id = "live-message", sender = "alice", content = "live", timestamp = Date(1))
        val noise = BitchatMessage(id = "noise-message", sender = "alice", content = "noise", timestamp = Date(2))
        val nostr = BitchatMessage(id = "nostr-message", sender = "alice", content = "nostr", timestamp = Date(3))

        AppStateStore.addPrivateMessage("live-peer", live)
        AppStateStore.addPrivateMessage("noise-hex", noise)
        AppStateStore.addPrivateMessage("nostr_alias", nostr)

        AppStateStore.unifyPrivateChatsIntoPeer("live-peer", listOf("noise-hex", "nostr_alias"))

        assertEquals(setOf("live-peer"), AppStateStore.privateMessages.value.keys)
        assertEquals(listOf(live, noise, nostr), AppStateStore.privateMessages.value["live-peer"])
    }

    @Test
    fun `noise hex private messages are stored under stable contact conversation`() {
        val noiseKeyHex = "00".repeat(32)
        val contactID = ContactIdentityResolver.contactConversationIdForNoiseKey(ByteArray(32))
        val message = BitchatMessage(id = "noise-message", sender = "alice", content = "hello", timestamp = Date(1))

        AppStateStore.addPrivateMessage(noiseKeyHex, message)

        assertEquals(setOf(contactID), AppStateStore.privateMessages.value.keys)
        assertEquals(listOf(message), AppStateStore.privateMessages.value[contactID])
    }

    @Test
    fun `canonicalized private chat history is chronological after alias merge`() {
        val noiseKeyHex = "01".repeat(32)
        val contactID = ContactIdentityResolver.contactConversationIdForNoiseKey(ByteArray(32) { 1 })
        val later = BitchatMessage(id = "later", sender = "alice", content = "later", timestamp = Date(3))
        val earlier = BitchatMessage(id = "earlier", sender = "alice", content = "earlier", timestamp = Date(1))

        AppStateStore.addPrivateMessage(contactID, later)
        AppStateStore.addPrivateMessage(noiseKeyHex, earlier)

        assertEquals(listOf(earlier, later), AppStateStore.privateMessages.value[contactID])
    }
}
