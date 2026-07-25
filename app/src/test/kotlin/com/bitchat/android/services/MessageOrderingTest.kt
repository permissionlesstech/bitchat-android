package com.bitchat.android.services

import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.util.MessageOrdering
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Date

/**
 * Regression tests for issue #525 (dups #425/#420/#302): incoming mesh messages were shown in
 * receive order instead of by their source packet timestamp, so a store-forwarded/gossip-synced
 * backlog appended at the bottom of the timeline interleaved with current messages.
 */
class MessageOrderingTest {

    @Before
    fun setUp() {
        AppStateStore.clear()
    }

    @After
    fun tearDown() {
        AppStateStore.clear()
    }

    private fun msg(id: String, tsMillis: Long, content: String = "m-$id"): BitchatMessage =
        BitchatMessage(
            id = id,
            sender = "alice",
            content = content,
            timestamp = Date(tsMillis),
            senderPeerID = "1122334455667788"
        )

    @Test
    fun `public timeline orders a store-forwarded backlog by timestamp`() {
        val now = 1_700_000_000_000L
        // A current message is already on screen...
        val current = msg("current", now, content = "current")
        // ...then a peer reconnects and replays an hour-old backlog out of order.
        val oldA = msg("old-a", now - 3_600_000L, content = "old-a")
        val oldB = msg("old-b", now - 1_800_000L, content = "old-b")

        AppStateStore.addPublicMessage(current)
        AppStateStore.addPublicMessage(oldB)
        AppStateStore.addPublicMessage(oldA)

        val result = AppStateStore.publicMessages.value
        assertEquals(listOf(oldA, oldB, current), result)
        // Timestamps must be non-decreasing.
        assertEquals(result.sortedBy { it.timestamp.time }, result)
    }

    @Test
    fun `channel timeline orders messages by timestamp`() {
        val now = 1_700_000_000_000L
        val c0 = msg("c0", now)
        val cOld = msg("c-old", now - 5_000L)
        val cMid = msg("c-mid", now - 2_000L)

        AppStateStore.addChannelMessage("#general", c0)
        AppStateStore.addChannelMessage("#general", cOld)
        AppStateStore.addChannelMessage("#general", cMid)

        assertEquals(
            listOf(cOld, cMid, c0),
            AppStateStore.channelMessages.value["#general"]
        )
    }

    @Test
    fun `dedup still drops repeats while keeping timestamp order`() {
        val now = 1_700_000_000_000L
        val a = msg("a", now)
        val b = msg("b", now - 1_000L)

        AppStateStore.addPublicMessage(a)
        AppStateStore.addPublicMessage(b)
        // Same id arriving again over a second transport path must be ignored.
        AppStateStore.addPublicMessage(a.copy(content = "dup-by-id"))
        // Same sender/timestamp/content with a fresh android id (request-sync replay) must be ignored.
        AppStateStore.addPublicMessage(b.copy(id = "b-replay"))

        assertEquals(listOf(b, a), AppStateStore.publicMessages.value)
    }

    @Test
    fun `equal timestamps keep insertion order (stable)`() {
        val ts = 1_700_000_000_000L
        val first = msg("first", ts, content = "first")
        val second = msg("second", ts, content = "second")
        val third = msg("third", ts, content = "third")

        // Direct helper check: inserting three equal-timestamp messages preserves arrival order.
        val list = mutableListOf<BitchatMessage>()
        MessageOrdering.insertByTimestamp(list, first)
        MessageOrdering.insertByTimestamp(list, second)
        MessageOrdering.insertByTimestamp(list, third)
        assertEquals(listOf(first, second, third), list)

        // And through the store add-path (distinct ids so dedup does not collapse them).
        AppStateStore.addPublicMessage(first)
        AppStateStore.addPublicMessage(second)
        AppStateStore.addPublicMessage(third)
        assertEquals(listOf(first, second, third), AppStateStore.publicMessages.value)
    }

    @Test
    fun `newer equal-timestamp message sorts after existing ones`() {
        val ts = 1_700_000_000_000L
        val existing = msg("existing", ts)
        val older = msg("older", ts - 10_000L)

        val list = mutableListOf(older, existing)
        val incomingSameAsExisting = msg("incoming", ts)
        MessageOrdering.insertByTimestamp(list, incomingSameAsExisting)

        assertEquals(listOf(older, existing, incomingSameAsExisting), list)
    }
}
