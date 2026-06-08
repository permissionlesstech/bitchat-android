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
}
