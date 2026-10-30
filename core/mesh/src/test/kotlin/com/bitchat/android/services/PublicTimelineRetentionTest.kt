package com.bitchat.android.services

import com.bitchat.android.model.BitchatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.mockito.kotlin.mock
import java.util.Date

class PublicTimelineRetentionTest {
    private val store = TestMeshStores(mock()).appStateStore

    @Test
    fun `public history keeps newest messages while recently evicted messages stay deduplicated`() {
        val count = AppStateStore.MAX_PUBLIC_TIMELINE_MESSAGES
        repeat(count + 10) { store.addPublicMessage(message(it)) }
        assertEquals((10 until count + 10).map { "fixture-$it" }, store.publicMessages.value.map { it.id })
        store.addPublicMessage(message(0))
        assertEquals(count, store.publicMessages.value.size)
        assertEquals("fixture-${count + 9}", store.publicMessages.value.last().id)
    }

    @Test
    fun `channel history and retained channel count are bounded by recent activity`() {
        val count = AppStateStore.MAX_PUBLIC_TIMELINE_MESSAGES
        repeat(count + 10) { store.addChannelMessage("fixture-channel", message(it)) }
        assertEquals(count, store.channelMessages.value.getValue("fixture-channel").size)
        repeat(AppStateStore.MAX_RETAINED_CHANNEL_TIMELINES - 1) {
            store.addChannelMessage("fixture-channel-$it", message(count + 10 + it))
        }
        store.addChannelMessage("fixture-channel", message(count + 100))
        store.addChannelMessage("fixture-new-channel", message(count + 101))
        assertEquals(AppStateStore.MAX_RETAINED_CHANNEL_TIMELINES, store.channelMessages.value.size)
        assertFalse("fixture-channel-0" in store.channelMessages.value)
        assertEquals(count, store.channelMessages.value.getValue("fixture-channel").size)
    }

    @Test
    fun `retained messages cannot be duplicated after the recent ID window expires`() {
        store.addChannelMessage("fixture-quiet", message(0))
        store.addPublicMessage(message(-1))
        val updated = message(-1).copy(content = "synthetic-updated-media")
        store.upsertPublicMessage(updated)
        repeat(10_001) { index ->
            store.addChannelMessage("fixture-busy-${index / 1_000}", message(index + 1))
        }

        store.addChannelMessage("fixture-quiet", message(0))
        store.addPublicMessage(updated)

        assertEquals(listOf(message(0)), store.channelMessages.value["fixture-quiet"])
        assertEquals(listOf(updated), store.publicMessages.value)
    }

    @Test
    fun `clearing state also clears public deduplication history`() {
        store.addPublicMessage(message(0))
        store.clear()
        store.addPublicMessage(message(0))
        assertEquals(listOf(message(0)), store.publicMessages.value)
    }

    @Test
    fun `clearing unknown channels cannot bypass the retained timeline limit`() {
        repeat(100) { store.clearChannelMessages("fixture-absent-$it") }
        assertEquals(0, store.channelMessages.value.size)
    }

    private fun message(index: Int) = BitchatMessage(
        id = "fixture-$index", sender = "fixture-sender", content = "synthetic-$index",
        timestamp = Date(index.toLong()), senderPeerID = "1111222233334444"
    )
}
