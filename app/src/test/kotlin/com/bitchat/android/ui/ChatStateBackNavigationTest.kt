package com.bitchat.android.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `canHandleBack` must agree with the branches of
 * [ChatViewModel.handleBackPressed]: the back handler is enabled from one and
 * the press consumed by the other.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatStateBackNavigationTest {

    private lateinit var scope: TestScope
    private lateinit var state: ChatState
    private lateinit var subscription: Job

    @Before
    fun setUp() {
        scope = TestScope(UnconfinedTestDispatcher())
        state = ChatState(scope)
        // Shared WhileSubscribed, so it only tracks its sources while collected.
        subscription = scope.launch { state.canHandleBack.collect { } }
    }

    @After
    fun tearDown() {
        subscription.cancel()
    }

    @Test
    fun `is false on the bare chat screen`() {
        assertFalse(state.canHandleBack.value)
    }

    @Test
    fun `is true while the app info dialog is open`() {
        state.setShowAppInfo(true)

        assertTrue(state.canHandleBack.value)
    }

    @Test
    fun `is true while the password prompt is open`() {
        state.setShowPasswordPrompt(true)

        assertTrue(state.canHandleBack.value)
    }

    @Test
    fun `is true while a private chat is selected`() {
        state.setSelectedPrivateChatPeer("peer-a")

        assertTrue(state.canHandleBack.value)
    }

    @Test
    fun `is true while the private chat sheet is open`() {
        state.setPrivateChatSheetPeer("peer-a")

        assertTrue(state.canHandleBack.value)
    }

    @Test
    fun `is true while a channel is open`() {
        state.setCurrentChannel("#bitchat")

        assertTrue(state.canHandleBack.value)
    }

    @Test
    fun `returns to false once the last overlay closes`() {
        state.setCurrentChannel("#bitchat")
        state.setShowAppInfo(true)

        state.setShowAppInfo(false)
        assertTrue("the channel is still open", state.canHandleBack.value)

        state.setCurrentChannel(null)
        assertFalse("nothing is left to unwind", state.canHandleBack.value)
    }
}
