package com.bitchat.android.ui

import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.services.AppStateStore
import java.util.Date
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class MessageManagerGeohashBlockTest {
    // Synthetic channels and pubkeys; no location or relay data is involved.
    private val blocked = "a".repeat(64)
    private val other = "b".repeat(64)

    @Before fun setUp() = AppStateStore.clear()
    @After fun tearDown() = AppStateStore.clear()

    @Test
    fun `blocking a sender hides their existing messages in every geohash timeline`() = runTest {
        val state = ChatState(backgroundScope)
        val manager = MessageManager(state)
        manager.addChannelMessage("geo:s00000", message("geo-1", blocked))
        manager.addChannelMessage("geo:s00000", message("geo-2", other))
        manager.addChannelMessage("geo:s00001", message("geo-3", blocked.uppercase()))
        manager.addChannelMessage("#fixture-channel", message("mesh-1", blocked))

        manager.removeGeohashMessagesFromSender(blocked)

        val expected = mapOf(
            "geo:s00000" to listOf("geo-2"),
            "geo:s00001" to emptyList(),
            "#fixture-channel" to listOf("mesh-1"),
        )
        assertEquals(expected, state.channelMessages.value.mapValues { (_, list) -> list.map { it.id } })
        assertEquals(expected, AppStateStore.channelMessages.value.mapValues { (_, list) -> list.map { it.id } })
    }

    private fun message(id: String, pubkey: String) = BitchatMessage(
        id = id, sender = "fixture-sender", content = "synthetic-$id",
        timestamp = Date(0), senderNostrPubkey = pubkey,
    )
}
