package com.bitchat.android.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A channel only renders on the Public tab, so every way into one has to bring that tab on screen.
 *
 * Getting this wrong is silent: the channel switches in state, but the user is left looking at
 * Chats (or People, or Settings) with no timeline in sight.
 */
@RunWith(RobolectricTestRunner::class)
class ChannelTabRoutingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val testScope = TestScope(UnconfinedTestDispatcher())
    private val state = ChatState(scope = testScope)
    private val channelManager = ChannelManager(
        state = state,
        messageManager = MessageManager(state = state),
        dataManager = DataManager(context = context),
        coroutineScope = testScope
    )

    @Test
    fun `defaults to the Public tab`() {
        assertEquals(AppTab.Public, state.getSelectedTabValue())
    }

    @Test
    fun `opening a channel from another tab shows the timeline`() {
        // The channel row in the Chats tab.
        state.setSelectedTab(AppTab.Chats)

        channelManager.switchToChannel("#general")

        assertEquals("#general", state.getCurrentChannelValue())
        assertEquals(AppTab.Public, state.getSelectedTabValue())
    }

    @Test
    fun `joining a channel from another tab shows the timeline`() {
        // The password prompt is hosted by the shell and can be confirmed from any tab.
        state.setSelectedTab(AppTab.Settings)

        channelManager.joinChannel("general", password = null, myPeerID = "peer-id")

        assertEquals("#general", state.getCurrentChannelValue())
        assertEquals(AppTab.Public, state.getSelectedTabValue())
    }

    @Test
    fun `leaving a channel does not move the user off their tab`() {
        channelManager.switchToChannel("#general")
        state.setSelectedTab(AppTab.People)

        channelManager.switchToChannel(null)

        assertEquals(AppTab.People, state.getSelectedTabValue())
    }
}
