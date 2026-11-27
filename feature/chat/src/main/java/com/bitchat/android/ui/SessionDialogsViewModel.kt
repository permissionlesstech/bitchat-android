package com.bitchat.android.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitchat.android.di.CurrentSession
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** What the session asks of the user, whichever screen raised it. */
@Immutable
data class SessionDialogsState(
    val showPasswordPrompt: Boolean = false,
    val passwordPromptChannel: String? = null,
    val legacyPrivateMediaConsent: LegacyPrivateMediaConsentRequest? = null,
)

sealed interface SessionDialogAction {
    /** Joins the prompted channel; on success the prompt closes. */
    data class SubmitChannelPassword(val channel: String, val password: String) : SessionDialogAction
    data object DismissPasswordPrompt : SessionDialogAction
    data class ApproveLegacyPrivateMedia(val requestID: String) : SessionDialogAction
    data class CancelLegacyPrivateMedia(val requestID: String) : SessionDialogAction
}

/**
 * The session's dialogs: the channel password prompt and the consent to send private media to an
 * older peer unencrypted. A command typed in any chat, or media sent from any chat, can raise
 * them, so [SessionDialogs] draws them once above navigation rather than in one screen.
 *
 * Kept with the screens' ViewModels, so a new session discards this one with them.
 */
@HiltViewModel
class SessionDialogsViewModel @Inject constructor(
    @CurrentSession private val state: ChatState,
    @CurrentSession private val sessionMesh: ChatSessionMesh,
    @CurrentSession private val channelManager: ChannelManager,
    @CurrentSession private val mediaSendingManager: MediaSendingManager,
) : ViewModel() {

    private fun uiStateNow() = SessionDialogsState(
        showPasswordPrompt = state.showPasswordPrompt.value,
        passwordPromptChannel = state.passwordPromptChannel.value,
        legacyPrivateMediaConsent = mediaSendingManager.legacyPrivateMediaConsent.value,
    )

    val uiState: StateFlow<SessionDialogsState> = combine(
        state.showPasswordPrompt,
        state.passwordPromptChannel,
        mediaSendingManager.legacyPrivateMediaConsent,
    ) { _, _, _ -> uiStateNow() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), uiStateNow())

    fun onAction(action: SessionDialogAction) {
        when (action) {
            is SessionDialogAction.SubmitChannelPassword -> {
                if (channelManager.joinChannel(action.channel, action.password, sessionMesh.unified.myPeerID)) {
                    state.clearPasswordPrompt()
                }
            }
            // Both Back and the dialog's own buttons clear the one flag, so it
            // cannot be left set by one path and cleared by another.
            SessionDialogAction.DismissPasswordPrompt -> state.clearPasswordPrompt()
            is SessionDialogAction.ApproveLegacyPrivateMedia ->
                mediaSendingManager.approveLegacyPrivateMedia(action.requestID)
            is SessionDialogAction.CancelLegacyPrivateMedia ->
                mediaSendingManager.cancelLegacyPrivateMedia(action.requestID)
        }
    }
}
