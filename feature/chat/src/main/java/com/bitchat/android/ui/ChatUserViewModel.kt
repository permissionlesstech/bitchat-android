package com.bitchat.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.navigation.Navigator
import com.bitchat.android.navigation.openPrivateChat
import com.bitchat.android.services.ContactDirectory
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class ChatUserUiState(
    /** The long-pressed message, or null when it is gone or there was none. */
    val message: BitchatMessage?,
    /** False on this device's own message: there is no one to act on. */
    val showUserActions: Boolean,
)

sealed interface ChatUserAction {
    data object PrivateMessage : ChatUserAction
    data object Slap : ChatUserAction
    data object Hug : ChatUserAction
    data object Block : ChatUserAction
}

/**
 * Actions on the user behind a long-pressed message: message them, slap,
 * hug or block. Takes the key's nickname and message id.
 */
@HiltViewModel(assistedFactory = ChatUserViewModel.Factory::class)
class ChatUserViewModel @AssistedInject constructor(
    @Assisted("nickname") private val nickname: String,
    @Assisted("messageId") messageId: String?,
    private val state: ChatState,
    private val mesh: ChatSessionMesh,
    private val geohashSession: GeohashSession,
    private val messageSender: MessageSender,
    private val navigator: Navigator,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted("nickname") nickname: String,
            @Assisted("messageId") messageId: String?,
        ): ChatUserViewModel
    }

    val uiState: StateFlow<ChatUserUiState> = combine(
        state.messages,
        state.channelMessages,
        state.nickname,
    ) { messages, channelMessages, myNickname ->
        uiStateFor(timelineMessage(messageId, messages, channelMessages), myNickname)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = uiStateFor(
            timelineMessage(messageId, state.messages.value, state.channelMessages.value),
            state.nickname.value,
        ),
    )

    fun onAction(action: ChatUserAction) {
        when (action) {
            ChatUserAction.PrivateMessage -> startPrivateMessage()
            ChatUserAction.Slap -> messageSender.send("/slap $nickname")
            ChatUserAction.Hug -> messageSender.send("/hug $nickname")
            ChatUserAction.Block ->
                if (state.selectedLocationChannel.value is ChannelID.Location) {
                    geohashSession.blockUserInGeohash(nickname)
                } else {
                    messageSender.send("/block $nickname")
                }
        }
    }

    private fun startPrivateMessage() {
        val message = uiState.value.message
        if (state.selectedLocationChannel.value is ChannelID.Location) {
            val senderPeerID = message?.senderPeerID
            if (senderPeerID?.startsWith("nostr:") == true) {
                geohashSession.startGeohashDMByShortId(senderPeerID.substring(6), ::openPrivateChat)
            } else {
                geohashSession.startGeohashDMByNickname(nickname, ::openPrivateChat)
            }
        } else {
            val peerID = message?.senderPeerID
                ?: mesh.unified.getPeerNicknames().entries.find { it.value == nickname }?.key
            peerID?.let(::openPrivateChat)
        }
    }

    private fun openPrivateChat(peerID: String) {
        navigator.openPrivateChat(ContactDirectory.canonicalConversationId(peerID))
    }

    private fun uiStateFor(message: BitchatMessage?, myNickname: String) = ChatUserUiState(
        message = message,
        showUserActions = message?.sender != myNickname,
    )
}
