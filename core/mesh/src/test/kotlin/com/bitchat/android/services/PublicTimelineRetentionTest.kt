package com.bitchat.android.services

import com.bitchat.android.model.BitchatMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.util.Date

class PublicTimelineRetentionTest {
    @Before fun setUp() = AppStateStore.clear()
    @After fun tearDown() = AppStateStore.clear()

    @Test
    fun `public history keeps newest messages while recently evicted messages stay deduplicated`() {
        val count = AppStateStore.MAX_PUBLIC_TIMELINE_MESSAGES
        repeat(count + 10) { AppStateStore.addPublicMessage(message(it)) }
        assertEquals((10 until count + 10).map { "fixture-$it" }, AppStateStore.publicMessages.value.map { it.id })
        AppStateStore.addPublicMessage(message(0))
        assertEquals(count, AppStateStore.publicMessages.value.size)
        assertEquals("fixture-${count + 9}", AppStateStore.publicMessages.value.last().id)
    }

    @Test
    fun `channel history and retained channel count are bounded by recent activity`() {
        val count = AppStateStore.MAX_PUBLIC_TIMELINE_MESSAGES
        repeat(count + 10) { AppStateStore.addChannelMessage("fixture-channel", message(it)) }
        assertEquals(count, AppStateStore.channelMessages.value.getValue("fixture-channel").size)
        repeat(AppStateStore.MAX_RETAINED_CHANNEL_TIMELINES - 1) {
            AppStateStore.addChannelMessage("fixture-channel-$it", message(count + 10 + it))
        }
        AppStateStore.addChannelMessage("fixture-channel", message(count + 100))
        AppStateStore.addChannelMessage("fixture-new-channel", message(count + 101))
        assertEquals(AppStateStore.MAX_RETAINED_CHANNEL_TIMELINES, AppStateStore.channelMessages.value.size)
        assertFalse("fixture-channel-0" in AppStateStore.channelMessages.value)
        assertEquals(count, AppStateStore.channelMessages.value.getValue("fixture-channel").size)
    }

    @Test
    fun `retained messages cannot be duplicated after the recent ID window expires`() {
        AppStateStore.addChannelMessage("fixture-quiet", message(0))
        AppStateStore.addPublicMessage(message(-1))
        val updated = message(-1).copy(content = "synthetic-updated-media")
        AppStateStore.upsertPublicMessage(updated)
        repeat(10_001) { index ->
            AppStateStore.addChannelMessage("fixture-busy-${index / 1_000}", message(index + 1))
        }

        AppStateStore.addChannelMessage("fixture-quiet", message(0))
        AppStateStore.addPublicMessage(updated)

        assertEquals(listOf(message(0)), AppStateStore.channelMessages.value["fixture-quiet"])
        assertEquals(listOf(updated), AppStateStore.publicMessages.value)
    }

    @Test
    fun `clearing state also clears public deduplication history`() {
        AppStateStore.addPublicMessage(message(0))
        AppStateStore.clear()
        AppStateStore.addPublicMessage(message(0))
        assertEquals(listOf(message(0)), AppStateStore.publicMessages.value)
    }

    @Test
    fun `clearing unknown channels cannot bypass the retained timeline limit`() {
        repeat(100) { AppStateStore.clearChannelMessages("fixture-absent-$it") }
        assertEquals(0, AppStateStore.channelMessages.value.size)
    }

    private fun message(index: Int) = BitchatMessage(
        id = "fixture-$index", sender = "fixture-sender", content = "synthetic-$index",
        timestamp = Date(index.toLong()), senderPeerID = "1111222233334444"
    )
}
