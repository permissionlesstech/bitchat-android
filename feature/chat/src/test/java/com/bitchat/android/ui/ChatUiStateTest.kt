package com.bitchat.android.ui

import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.geohash.GeohashChannel
import com.bitchat.android.geohash.GeohashChannelLevel
import com.bitchat.android.model.BitchatMessage
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatUiStateTest {

    private fun msg(id: String) = BitchatMessage(
        id = id,
        sender = "alice",
        content = "hello",
        timestamp = Date(0),
    )

    private val mesh = listOf(msg("mesh"))
    private val channels = mapOf(
        "#general" to listOf(msg("general")),
        "geo:s00000" to listOf(msg("geo")),
    )
    private val location = ChannelID.Location(GeohashChannel(GeohashChannelLevel.CITY, "s00000"))

    private fun timeline(currentChannel: String?, selected: ChannelID?) = timelineStateOf(
        meshMessages = mesh,
        channelMessages = channels,
        currentChannel = currentChannel,
        selectedPrivatePeer = null,
        selectedLocationChannel = selected,
        nickname = "me",
        connectedPeers = emptyList(),
        peerNicknames = emptyMap(),
        geohashPeople = emptyList(),
    )

    @Test
    fun `an open channel is shown over the selected location`() {
        val state = timeline(currentChannel = "#general", selected = location)

        assertEquals(listOf("general"), state.messages.map { it.id })
        assertEquals("channel:#general", state.conversationKey)
    }

    @Test
    fun `a geohash location shows its own timeline`() {
        val state = timeline(currentChannel = null, selected = location)

        assertEquals(listOf("geo"), state.messages.map { it.id })
        assertEquals("geo:s00000", state.conversationKey)
    }

    @Test
    fun `the mesh timeline is the fallback`() {
        val state = timeline(currentChannel = null, selected = ChannelID.Mesh)

        assertEquals(listOf("mesh"), state.messages.map { it.id })
        assertEquals(MESH_CONVERSATION_KEY, state.conversationKey)
    }

    @Test
    fun `a channel with no messages yet shows an empty timeline, not the mesh`() {
        val state = timeline(currentChannel = "#quiet", selected = ChannelID.Mesh)

        assertTrue(state.messages.isEmpty())
        assertEquals("channel:#quiet", state.conversationKey)
    }

    private fun header(connected: List<String>, unreadPrivate: Set<String>) = headerStateOf(
        connectedPeers = connected,
        myPeerID = "self",
        joinedChannels = emptySet(),
        unreadChannelMessages = emptyMap(),
        unreadPrivateMessages = unreadPrivate,
        isConnected = true,
        selectedLocationChannel = ChannelID.Mesh,
        geohashPeople = emptyList(),
    )

    @Test
    fun `the peer count leaves out this device`() {
        val state = header(connected = listOf("self", "peer-a", "peer-b"), unreadPrivate = emptySet())

        assertEquals(listOf("peer-a", "peer-b"), state.connectedPeers)
    }

    @Test
    fun `any unread private conversation raises the envelope`() {
        assertTrue(header(emptyList(), unreadPrivate = setOf("peer-a")).hasUnreadPrivateMessages)
        assertFalse(header(emptyList(), unreadPrivate = emptySet()).hasUnreadPrivateMessages)
    }
}
