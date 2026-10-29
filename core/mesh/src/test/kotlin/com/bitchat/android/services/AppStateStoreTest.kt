package com.bitchat.android.services

import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import kotlinx.coroutines.runBlocking
import java.util.Date

class AppStateStoreTest {
    private val store = TestMeshStores(mock()).appStateStore

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

        store.addPublicMessage(originalDelivery)
        store.addPublicMessage(requestSyncReplay)

        assertEquals(listOf(originalDelivery), store.publicMessages.value)
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

        store.addPublicMessage(first)
        store.addPublicMessage(second)

        assertEquals(listOf(first, second), store.publicMessages.value)
    }

    @Test
    fun `peer list merges transport updates instead of overwriting`() {
        store.setTransportPeers("WIFI", listOf("wifi-peer"))
        store.setTransportPeers("BLE", emptyList())

        assertEquals(listOf("wifi-peer"), store.peers.value)

        store.setTransportPeers("BLE", listOf("ble-peer"))

        assertEquals(listOf("wifi-peer", "ble-peer"), store.peers.value)
    }

    @Test
    fun `direct peers union across transports`() {
        store.setTransportDirectPeers("BLE", listOf("ble-1", "shared"))
        store.setTransportDirectPeers("WIFI", listOf("wifi-1", "shared"))

        assertEquals(
            setOf("ble-1", "wifi-1", "shared"),
            store.getDirectPeers()
        )
        assertEquals(
            setOf("ble-1", "wifi-1", "shared"),
            store.directPeers.value
        )
    }

    @Test
    fun `clearing one transport keeps the other transport direct peers`() {
        store.setTransportDirectPeers("BLE", listOf("ble-1"))
        store.setTransportDirectPeers("WIFI", listOf("wifi-1"))

        store.clearTransportDirectPeers("WIFI")

        assertEquals(setOf("ble-1"), store.getDirectPeers())
    }

    @Test
    fun `latest direct peer set replaces previous set for same transport`() {
        store.setTransportDirectPeers("WIFI", listOf("wifi-1", "wifi-2"))
        store.setTransportDirectPeers("WIFI", listOf("wifi-3"))

        assertEquals(setOf("wifi-3"), store.getDirectPeers())
    }

    @Test
    fun `private chat aliases merge into canonical peer`() {
        val live = BitchatMessage(id = "live-message", sender = "alice", content = "live", timestamp = Date(1))
        val noise = BitchatMessage(id = "noise-message", sender = "alice", content = "noise", timestamp = Date(2))
        val nostr = BitchatMessage(id = "nostr-message", sender = "alice", content = "nostr", timestamp = Date(3))

        store.addPrivateMessage("live-peer", live)
        store.addPrivateMessage("noise-hex", noise)
        store.addPrivateMessage("nostr_alias", nostr)

        store.unifyPrivateChatsIntoPeer("live-peer", listOf("noise-hex", "nostr_alias"))

        assertEquals(setOf("live-peer"), store.privateMessages.value.keys)
        assertEquals(listOf(live, noise, nostr), store.privateMessages.value["live-peer"])
    }

    @Test
    fun `noise hex private messages are stored under stable contact conversation`() {
        val noiseKeyHex = "00".repeat(32)
        val contactID = ContactIdentityResolver.contactConversationIdForNoiseKey(ByteArray(32))
        val message = BitchatMessage(id = "noise-message", sender = "alice", content = "hello", timestamp = Date(1))

        store.addPrivateMessage(noiseKeyHex, message)

        assertEquals(setOf(contactID), store.privateMessages.value.keys)
        assertEquals(listOf(message), store.privateMessages.value[contactID])
    }

    @Test
    fun `live nickname updates retained conversation metadata`() {
        val message = BitchatMessage(
            id = "rename-message",
            sender = "Alice Old",
            content = "hello",
            timestamp = Date(1),
            isPrivate = true
        )
        store.addPrivateMessage("peer-a", message)

        store.updatePrivateConversationDisplayNames(
            mapOf("peer-a" to "Alice New")
        )

        assertEquals(
            "Alice New",
            store.privateConversationDisplayNames.value.getValue("peer-a")
        )
    }

    @Test
    fun `restored unread and nickname state canonicalize with message aliases`() {
        val noiseKeyHex = "02".repeat(32)
        val contactID = ContactIdentityResolver.contactConversationIdForNoiseKey(
            ByteArray(32) { 2 }
        )
        val message = BitchatMessage(
            id = "restored-alias-message",
            sender = "Alice",
            content = "hello",
            timestamp = Date(1),
            isPrivate = true
        )

        store.restorePrivateConversations(
            PersistedConversationSnapshot(
                chats = mapOf(noiseKeyHex to listOf(message)),
                readMessageIDs = emptySet(),
                arrivalOrder = listOf(message.id),
                deletedMessageIDs = emptySet(),
                displayNames = mapOf(noiseKeyHex to "Alice Renamed"),
                unreadCounts = mapOf(noiseKeyHex to 1)
            )
        )

        assertEquals(setOf(contactID), store.privateMessages.value.keys)
        assertEquals(mapOf(contactID to 1), store.unreadPrivateMessageCounts.value)
        assertEquals(
            mapOf(contactID to "Alice Renamed"),
            store.privateConversationDisplayNames.value
        )
    }

    @Test
    fun `canonicalized private chat history keeps arrival order when peer clocks differ`() {
        val noiseKeyHex = "01".repeat(32)
        val contactID = ContactIdentityResolver.contactConversationIdForNoiseKey(ByteArray(32) { 1 })
        val firstArrival = BitchatMessage(
            id = "first-arrival",
            sender = "alice",
            content = "sent from a clock that is ahead",
            timestamp = Date(3)
        )
        val secondArrival = BitchatMessage(
            id = "second-arrival",
            sender = "alice",
            content = "sent from a clock that is behind",
            timestamp = Date(1)
        )

        store.addPrivateMessage(contactID, firstArrival)
        store.addPrivateMessage(noiseKeyHex, secondArrival)

        assertEquals(
            listOf(firstArrival, secondArrival),
            store.privateMessages.value[contactID]
        )
    }

    @Test
    fun `background receipt status persists and cannot be downgraded`() {
        val message = BitchatMessage(
            id = "outgoing-message",
            sender = "bob",
            content = "hello",
            timestamp = Date(1),
            isPrivate = true,
            deliveryStatus = DeliveryStatus.Sending
        )
        store.addPrivateMessage("peer-a", message)

        store.updatePrivateMessageStatus(
            message.id,
            DeliveryStatus.Delivered("peer-a", Date(2))
        )
        store.updatePrivateMessageStatus(
            message.id,
            DeliveryStatus.Read("peer-a", Date(3))
        )
        store.updatePrivateMessageStatus(
            message.id,
            DeliveryStatus.Delivered("peer-a", Date(4))
        )

        val status = store.privateMessages.value
            .values
            .flatten()
            .single()
            .deliveryStatus
        assertTrue(status is DeliveryStatus.Read)
    }

    @Test
    fun `in flight message can become failed without overwriting confirmed delivery`() {
        val sending = BitchatMessage(
            id = "send-failure",
            sender = "me",
            content = "hello",
            timestamp = Date(1),
            isPrivate = true,
            deliveryStatus = DeliveryStatus.Sending
        )
        store.addPrivateMessage("peer-a", sending)

        store.updatePrivateMessageStatus(
            sending.id,
            DeliveryStatus.Failed("network unavailable")
        )
        assertTrue(
            store.privateMessages.value
                .getValue("peer-a")
                .single()
                .deliveryStatus is DeliveryStatus.Failed
        )

        store.updatePrivateMessageStatus(
            sending.id,
            DeliveryStatus.Delivered("alice", Date(2))
        )
        store.updatePrivateMessageStatus(
            sending.id,
            DeliveryStatus.Failed("late timeout")
        )
        assertTrue(
            store.privateMessages.value
                .getValue("peer-a")
                .single()
                .deliveryStatus is DeliveryStatus.Delivered
        )
    }

    @Test
    fun `closing a conversation releases full payload history but keeps its summary`() {
        repeat(3) { index ->
            store.addPrivateMessage(
                "peer-a",
                BitchatMessage(
                    id = "history-$index",
                    sender = "alice",
                    content = "message $index",
                    timestamp = Date(index.toLong()),
                    isPrivate = true
                )
            )
        }

        store.releasePrivateConversationHistory("peer-a")

        assertEquals(
            listOf("history-2"),
            store.privateMessages.value.getValue("peer-a").map { it.id }
        )
    }

    @Test
    fun `long lived process applies the same bounded private history policy`() {
        store.setNickname("me")
        repeat(ConversationDatabase.MAX_MESSAGES_PER_CONVERSATION) { index ->
            store.addPrivateMessage(
                "peer-a",
                BitchatMessage(
                    id = "incoming-$index",
                    sender = "alice",
                    content = "message",
                    timestamp = Date(index.toLong()),
                    isPrivate = true
                )
            )
        }
        store.addPrivateMessage(
            "peer-a",
            BitchatMessage(
                id = "latest-outgoing",
                sender = "me",
                content = "latest",
                timestamp = Date(Long.MAX_VALUE),
                isPrivate = true
            )
        )

        val retained = store.privateMessages.value.getValue("peer-a")
        assertEquals(ConversationDatabase.MAX_MESSAGES_PER_CONVERSATION, retained.size)
        assertEquals("incoming-1", retained.first().id)
        assertEquals("latest-outgoing", retained.last().id)
    }

    @Test
    fun `private message admission is atomic and can durably classify known read messages`() {
        val message = BitchatMessage(
            id = "same-transport-message",
            sender = "alice",
            content = "hello",
            timestamp = Date(1L),
            isPrivate = true
        )

        assertTrue(store.addPrivateMessage("peer-a", message, forceRead = true))
        assertFalse(store.addPrivateMessage("peer-a", message))

        assertEquals(1, store.privateMessages.value.getValue("peer-a").size)
        assertTrue(store.isPrivateMessageRead(message.id))
    }

    @Test
    fun `panic clear rejects private messages until explicitly resumed`() {
        val beforePanic = BitchatMessage(
            id = "before-panic",
            sender = "alice",
            content = "erase me",
            timestamp = Date(1L),
            isPrivate = true
        )
        val duringPanic = beforePanic.copy(id = "during-panic")
        val afterPanic = beforePanic.copy(id = "after-panic")

        assertTrue(store.addPrivateMessage("peer-a", beforePanic))
        store.updatePrivateConversationDisplayNames(
            mapOf("peer-a" to "Alice")
        )
        assertTrue(runBlocking { store.panicClearPrivateConversations() })
        assertTrue(store.privateMessages.value.isEmpty())
        assertTrue(store.privateConversationDisplayNames.value.isEmpty())
        assertFalse(store.addPrivateMessage("peer-a", duringPanic))

        store.resumePrivateConversationsAfterPanic()

        assertTrue(store.addPrivateMessage("peer-a", afterPanic))
        assertEquals(
            listOf(afterPanic),
            store.privateMessages.value.getValue("peer-a")
        )
    }
}
