package com.bitchat.android.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The order in which Back unwinds the chat screen's overlays.
 *
 * [ChatBackUnwinder.handle] performs whatever [pendingBackAction] names,
 * so pinning the decision here pins the behaviour. The order is the contract the
 * route conversion has to preserve when these overlays stop being booleans and
 * become back-stack entries.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatStateBackNavigationTest {

    private lateinit var scope: TestScope
    private lateinit var state: ChatState
    private var subscription: Job? = null

    @Before
    fun setUp() {
        scope = TestScope(UnconfinedTestDispatcher())
        state = ChatState(scope)
    }

    @After
    fun tearDown() {
        subscription?.cancel()
    }

    /** The flow is WhileSubscribed, so it only tracks its sources while collected. */
    private fun observePendingBackAction() {
        subscription = scope.launch { state.pendingBackAction.collect { } }
    }

    @Test
    fun `the bare chat screen leaves Back to the system`() {
        assertEquals(BackAction.None, state.pendingBackAction())
    }

    @Test
    fun `the password prompt is unwound`() {
        state.setShowPasswordPrompt(true)

        assertEquals(BackAction.DismissPasswordPrompt, state.pendingBackAction())
    }

    @Test
    fun `clearing the password prompt hands Back to the next overlay`() {
        state.setCurrentChannel("#secret")
        state.setPasswordPromptChannel("#secret")
        state.setShowPasswordPrompt(true)
        assertEquals(BackAction.DismissPasswordPrompt, state.pendingBackAction())

        state.clearPasswordPrompt()

        assertEquals(BackAction.ExitChannel, state.pendingBackAction())
        assertNull(state.getPasswordPromptChannelValue())
    }

    // A private chat started with /msg selects the conversation without opening
    // its screen, which is a route with its own Back handling.
    @Test
    fun `a selected private chat is unwound`() {
        state.setSelectedPrivateChatPeer("peer-a")

        assertEquals(BackAction.ExitPrivateChat, state.pendingBackAction())
    }

    @Test
    fun `an open channel is unwound`() {
        state.setCurrentChannel("#bitchat")

        assertEquals(BackAction.ExitChannel, state.pendingBackAction())
    }

    @Test
    fun `overlays unwind innermost first and then hand Back back to the system`() {
        state.setCurrentChannel("#bitchat")
        state.setSelectedPrivateChatPeer("peer-a")
        state.setShowPasswordPrompt(true)

        assertEquals(BackAction.DismissPasswordPrompt, state.pendingBackAction())

        state.setShowPasswordPrompt(false)
        assertEquals(BackAction.ExitPrivateChat, state.pendingBackAction())

        state.setSelectedPrivateChatPeer(null)
        assertEquals(BackAction.ExitChannel, state.pendingBackAction())

        state.setCurrentChannel(null)
        assertEquals(BackAction.None, state.pendingBackAction())
    }

    @Test
    fun `the observable action agrees with the decision it gates`() {
        observePendingBackAction()

        assertEquals(BackAction.None, state.pendingBackAction.value)

        state.setCurrentChannel("#bitchat")
        assertEquals(BackAction.ExitChannel, state.pendingBackAction.value)

        state.setShowPasswordPrompt(true)
        assertEquals(BackAction.DismissPasswordPrompt, state.pendingBackAction.value)
    }

    @Test
    fun `the observable action reaches None so the handler stops claiming Back`() {
        observePendingBackAction()
        state.setCurrentChannel("#bitchat")

        state.setCurrentChannel(null)

        // The chat back handler is enabled from this. If it stayed non-None with
        // nothing open, Back would be swallowed instead of leaving the app.
        assertEquals(BackAction.None, state.pendingBackAction.value)
    }
}
