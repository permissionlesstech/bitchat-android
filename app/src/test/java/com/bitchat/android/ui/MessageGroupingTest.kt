package com.bitchat.android.ui

import androidx.compose.ui.unit.dp
import com.bitchat.android.model.BitchatMessage
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Grouping decides whether a message repeats its author's name. Getting it wrong either spams
 * the transcript with redundant labels or, worse, silently attributes one person's message to
 * another — hence the emphasis on the negative cases here.
 */
class MessageGroupingTest {

    private val base = 1_700_000_000_000L

    private fun message(
        sender: String = "alice",
        senderPeerId: String? = "peer-alice",
        offsetMs: Long = 0L,
        isPrivate: Boolean = false,
        channel: String? = null,
        content: String = "hello",
    ) = BitchatMessage(
        sender = sender,
        content = content,
        timestamp = Date(base + offsetMs),
        isPrivate = isPrivate,
        senderPeerID = senderPeerId,
        channel = channel,
    )

    @Test
    fun `first message in a list never groups`() {
        assertFalse(MessageGrouping.shouldGroup(previous = null, current = message()))
    }

    @Test
    fun `consecutive messages from the same peer group`() {
        val first = message(offsetMs = 0)
        val second = message(offsetMs = 30_000)

        assertTrue(MessageGrouping.shouldGroup(first, second))
    }

    @Test
    fun `messages from different peers do not group`() {
        val first = message(sender = "alice", senderPeerId = "peer-alice")
        val second = message(sender = "bob", senderPeerId = "peer-bob", offsetMs = 1_000)

        assertFalse(MessageGrouping.shouldGroup(first, second))
    }

    @Test
    fun `same nickname but different peer id does not group`() {
        // Two people can pick the same nickname; peer ID is the authority.
        val first = message(sender = "alice", senderPeerId = "peer-one")
        val second = message(sender = "alice", senderPeerId = "peer-two", offsetMs = 1_000)

        assertFalse(MessageGrouping.shouldGroup(first, second))
    }

    @Test
    fun `falls back to display name when peer ids are unavailable`() {
        val first = message(sender = "alice#04af", senderPeerId = null)
        val second = message(sender = "alice#04af", senderPeerId = null, offsetMs = 1_000)
        val other = message(sender = "bob#1122", senderPeerId = null, offsetMs = 2_000)

        assertTrue(MessageGrouping.shouldGroup(first, second))
        assertFalse(MessageGrouping.shouldGroup(second, other))
    }

    @Test
    fun `messages outside the grouping window start a new group`() {
        val first = message(offsetMs = 0)
        val justInside = message(offsetMs = MessageGrouping.GROUPING_WINDOW_MS)
        val justOutside = message(offsetMs = MessageGrouping.GROUPING_WINDOW_MS + 1)

        assertTrue(MessageGrouping.shouldGroup(first, justInside))
        assertFalse(MessageGrouping.shouldGroup(first, justOutside))
    }

    @Test
    fun `out of order timestamps do not group`() {
        // Mesh delivery can surface messages out of order; a negative delta means we cannot
        // reason about adjacency, so fall back to showing the sender.
        val later = message(offsetMs = 60_000)
        val earlier = message(offsetMs = 0)

        assertFalse(MessageGrouping.shouldGroup(later, earlier))
    }

    @Test
    fun `system messages never group in either direction`() {
        val system = message(sender = "system", senderPeerId = null, content = "tor started")
        val user = message(offsetMs = 1_000)

        assertFalse(MessageGrouping.shouldGroup(system, user))
        assertFalse(MessageGrouping.shouldGroup(user, system.copyWithOffset(2_000)))
        assertFalse(MessageGrouping.shouldGroup(system, system.copyWithOffset(1_000)))
    }

    @Test
    fun `grouping does not cross the public private boundary`() {
        val public = message(isPrivate = false)
        val private = message(isPrivate = true, offsetMs = 1_000)

        assertFalse(MessageGrouping.shouldGroup(public, private))
    }

    @Test
    fun `grouping does not cross channels`() {
        val inGeneral = message(channel = "#general")
        val inRandom = message(channel = "#random", offsetMs = 1_000)
        val inNoChannel = message(channel = null, offsetMs = 2_000)

        assertFalse(MessageGrouping.shouldGroup(inGeneral, inRandom))
        assertFalse(MessageGrouping.shouldGroup(inGeneral, inNoChannel))
    }

    @Test
    fun `peer id comparison ignores case`() {
        val first = message(senderPeerId = "PEER-Alice")
        val second = message(senderPeerId = "peer-alice", offsetMs = 1_000)

        assertTrue(MessageGrouping.shouldGroup(first, second))
    }

    // MARK: - Spacing

    @Test
    fun `spacing is zero for the very first row`() {
        assertEquals(0.dp, MessageGrouping.topSpacingFor(isGrouped = false, isFirstInList = true))
        assertEquals(0.dp, MessageGrouping.topSpacingFor(isGrouped = true, isFirstInList = true))
    }

    @Test
    fun `all transcript rows use the exported eight dp rhythm`() {
        val grouped = MessageGrouping.topSpacingFor(isGrouped = true, isFirstInList = false)
        val newGroup = MessageGrouping.topSpacingFor(isGrouped = false, isFirstInList = false)

        assertEquals(8.dp, grouped)
        assertEquals(8.dp, newGroup)
        assertEquals(grouped, newGroup)
        assertEquals(8.dp, MessageGrouping.SENDER_TOP_PADDING)
        assertEquals(4.dp, MessageGrouping.SENDER_TO_BODY_SPACING)
    }

    private fun BitchatMessage.copyWithOffset(offsetMs: Long) =
        copy(timestamp = Date(base + offsetMs))
}
