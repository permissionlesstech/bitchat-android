package com.bitchat.android.ui

import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.geohash.GeohashChannel
import com.bitchat.android.geohash.GeohashChannelLevel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.mock

class ChannelExitTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val testScope = TestScope(UnconfinedTestDispatcher())
    private val state = ChatState(scope = testScope)
    private val channelManager = ChannelManager(
        state = state,
        messageManager = MessageManager(state = state),
        dataManager = mock(),
        coroutineScope = testScope,
    )

    @Test
    fun `exiting a joined channel clears it from the timeline`() {
        state.setCurrentChannel("#test")

        channelManager.exitCurrentChannel()

        assertNull(state.getCurrentChannelValue())
    }

    @Test
    fun `exiting a joined channel keeps the selected location`() {
        val location = ChannelID.Location(GeohashChannel(GeohashChannelLevel.CITY, "9q8yy"))
        state.setCurrentChannel("#test")
        state.setSelectedLocationChannel(location)

        channelManager.exitCurrentChannel()

        assertEquals(location, state.selectedLocationChannel.value)
    }

    @Test
    fun `exiting a joined channel keeps the open private chat`() {
        state.setCurrentChannel("#test")
        state.setSelectedPrivateChatPeer("peer-a")

        channelManager.exitCurrentChannel()

        assertEquals("peer-a", state.getSelectedPrivateChatPeerValue())
    }
}
