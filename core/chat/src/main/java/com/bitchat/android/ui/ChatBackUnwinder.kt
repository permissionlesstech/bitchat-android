package com.bitchat.android.ui

import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * What Back unwinds on the chat screen before the navigator pops a route: the
 * join-password dialog, then a private chat, then a channel, in the order
 * [backActionFor] ranks them.
 *
 * Moved out of ChatViewModel so the Activity's back handler, which must be
 * composed after NavDisplay, does not need the chat screen's ViewModel.
 */
@ActivityRetainedScoped
class ChatBackUnwinder @Inject constructor(
    private val state: ChatState,
    private val privateChatSession: PrivateChatSession,
    private val channelManager: ChannelManager,
) {

    /** What Back would unwind next; drives the chat screen's back handler. */
    val pendingBackAction: StateFlow<BackAction> = state.pendingBackAction

    /**
     * Unwinds one step. Returns true if the press was handled, false if it
     * should pass on to the navigator.
     */
    fun handle(): Boolean {
        return when (state.pendingBackAction()) {
            BackAction.DismissPasswordPrompt -> {
                state.clearPasswordPrompt()
                true
            }
            BackAction.ExitPrivateChat -> {
                privateChatSession.end()
                true
            }
            BackAction.ExitChannel -> {
                channelManager.switchToChannel(null)
                true
            }
            BackAction.None -> false
        }
    }
}
