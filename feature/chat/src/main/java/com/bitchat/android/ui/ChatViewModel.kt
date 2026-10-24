package com.bitchat.android.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitchat.android.features.voice.VoiceRecorder
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.navigation.Navigator
import com.bitchat.android.navigation.openPrivateChat
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.ConversationListPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The chat screen's state holder: one [ChatUiState] out, [ChatAction]s in, and
 * [ChatEvent]s for the composer's local text.
 *
 * The session behind the screen, its state and managers, lives in the
 * Activity's retained scope and is shared with the other screens' ViewModels;
 * this class only reads it and forwards the screen's requests.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val sessionMesh: ChatSessionMesh,
    private val conversationListPreferences: ConversationListPreferences,
    // The unread shortcut opens a private chat from here rather than from a
    // screen, because it has to pick the conversation first.
    private val navigator: Navigator,
    private val state: ChatState,
    private val dataManager: DataManager,
    private val channelManager: ChannelManager,
    private val commandProcessor: CommandProcessor,
    private val mediaSendingManager: MediaSendingManager,
    private val geohashSession: GeohashSession,
    private val messageSender: MessageSender,
    private val composerMedia: ComposerMedia,
    private val panicClear: PanicClear,
    sessionStartup: ChatSessionStartup,
) : ViewModel() {

    private companion object {
        const val TAG = "ChatViewModel"
    }

    private val mesh: MeshService
        get() = sessionMesh.unified

    init {
        // Idempotent: a recreated ViewModel finds the session already running.
        sessionStartup.start()
    }

    // Each group is rebuilt from the current values whenever one of its inputs
    // changes, and seeded the same way, so the first frame shows the session's
    // state rather than an empty default.
    private fun timelineNow() = timelineStateOf(
        meshMessages = state.messages.value,
        channelMessages = state.channelMessages.value,
        currentChannel = state.currentChannel.value,
        selectedPrivatePeer = state.selectedPrivateChatPeer.value,
        selectedLocationChannel = state.selectedLocationChannel.value,
        nickname = state.nickname.value,
        connectedPeers = state.connectedPeers.value,
        peerNicknames = state.peerNicknames.value,
        geohashPeople = geohashSession.geohashPeople.value,
    )

    private fun headerNow() = headerStateOf(
        connectedPeers = state.connectedPeers.value,
        myPeerID = mesh.myPeerID,
        joinedChannels = state.joinedChannels.value,
        unreadChannelMessages = state.unreadChannelMessages.value,
        unreadPrivateMessages = state.unreadPrivateMessages.value,
        isConnected = state.isConnected.value,
        selectedLocationChannel = state.selectedLocationChannel.value,
        geohashPeople = geohashSession.geohashPeople.value,
    )

    private fun composerNow() = ComposerState(
        showCommandSuggestions = state.showCommandSuggestions.value,
        commandSuggestions = state.commandSuggestions.value,
        showMentionSuggestions = state.showMentionSuggestions.value,
        mentionSuggestions = state.mentionSuggestions.value,
    )

    private fun dialogsNow() = DialogState(
        showPasswordPrompt = state.showPasswordPrompt.value,
        passwordPromptChannel = state.passwordPromptChannel.value,
        legacyPrivateMediaConsent = mediaSendingManager.legacyPrivateMediaConsent.value,
    )

    private fun <T> groupOf(inputs: List<Flow<Any?>>, now: () -> T): Flow<T> =
        combine(inputs) { now() }

    val uiState: StateFlow<ChatUiState> = combine(
        groupOf(
            listOf(
                state.messages, state.channelMessages, state.currentChannel,
                state.selectedPrivateChatPeer, state.selectedLocationChannel, state.nickname,
                state.connectedPeers, state.peerNicknames, geohashSession.geohashPeople,
            ),
            ::timelineNow,
        ),
        groupOf(
            listOf(
                state.connectedPeers, state.joinedChannels, state.unreadChannelMessages,
                state.unreadPrivateMessages, state.isConnected, state.selectedLocationChannel,
                geohashSession.geohashPeople,
            ),
            ::headerNow,
        ),
        groupOf(
            listOf(
                state.showCommandSuggestions, state.commandSuggestions,
                state.showMentionSuggestions, state.mentionSuggestions,
            ),
            ::composerNow,
        ),
        groupOf(
            listOf(
                state.showPasswordPrompt, state.passwordPromptChannel,
                mediaSendingManager.legacyPrivateMediaConsent,
            ),
            ::dialogsNow,
        ),
        ::ChatUiState,
    ).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ChatUiState(timelineNow(), headerNow(), composerNow(), dialogsNow()),
    )

    private val _events = Channel<ChatEvent>(Channel.BUFFERED)
    val events: Flow<ChatEvent> = _events.receiveAsFlow()

    /** Renders media and resolves mentions in the timeline's rows. */
    val meshService: MeshService
        get() = mesh

    /** The saved composer draft of [conversationID], read when the composer is created. */
    fun conversationDraft(conversationID: String?): String =
        conversationListPreferences.composerDraft(conversationID)

    fun createVoiceRecorder(toPeerIDOrNull: String?, channelOrNull: String?): VoiceRecorder =
        composerMedia.createVoiceRecorder(toPeerIDOrNull, channelOrNull)

    fun onAction(action: ChatAction) {
        when (action) {
            is ChatAction.ComposerTextChanged -> {
                conversationListPreferences.saveComposerDraft(action.conversationID, action.text)
                commandProcessor.updateCommandSuggestions(action.text)
                commandProcessor.updateMentionSuggestions(action.text, mesh)
            }
            is ChatAction.Send -> messageSender.send(action.content) { accepted ->
                if (accepted) {
                    conversationListPreferences.saveComposerDraft(action.conversationID, "")
                    // Clearing the field in code does not report a text change,
                    // so the popups have to be dismissed here.
                    commandProcessor.clearSuggestions()
                    emit(ChatEvent.MessageSent)
                }
            }
            is ChatAction.SelectCommandSuggestion -> emit(
                ChatEvent.ReplaceComposerText(
                    commandProcessor.selectCommandSuggestion(action.suggestion)
                )
            )
            is ChatAction.SelectMentionSuggestion -> emit(
                ChatEvent.ReplaceComposerText(
                    commandProcessor.selectMentionSuggestion(action.nickname, action.currentText)
                )
            )
            is ChatAction.SendVoiceNote ->
                composerMedia.sendVoiceNote(action.peerID, action.channel, action.path)
            is ChatAction.SendImageNote ->
                composerMedia.sendImageNote(action.peerID, action.channel, action.path)
            is ChatAction.SendFileNote ->
                composerMedia.sendFileNote(action.peerID, action.channel, action.path)
            is ChatAction.CancelMediaSend -> composerMedia.cancelMediaSend(action.messageID)
            is ChatAction.ApproveLegacyPrivateMedia ->
                mediaSendingManager.approveLegacyPrivateMedia(action.requestID)
            is ChatAction.CancelLegacyPrivateMedia ->
                mediaSendingManager.cancelLegacyPrivateMedia(action.requestID)
            is ChatAction.SubmitChannelPassword -> {
                if (channelManager.joinChannel(action.channel, action.password, mesh.myPeerID)) {
                    state.clearPasswordPrompt()
                }
            }
            // Both Back and the dialog's own buttons clear the one flag, so it
            // cannot be left set by one path and cleared by another. While it is
            // set, [backActionFor] ranks it above exiting a private chat or a channel.
            ChatAction.DismissPasswordPrompt -> state.clearPasswordPrompt()
            is ChatAction.SetNickname -> {
                state.setNickname(action.nickname)
                dataManager.saveNickname(action.nickname)
                mesh.sendBroadcastAnnounce()
            }
            is ChatAction.LeaveChannel -> {
                channelManager.leaveChannel(action.channel)
                mesh.sendMessage("left ${action.channel}", emptyList(), null)
            }
            ChatAction.ExitChannel -> channelManager.switchToChannel(null)
            ChatAction.OpenLatestUnreadPrivateChat -> openLatestUnreadPrivateChat()
            ChatAction.PanicClear -> panicClear.run()
        }
    }

    private fun emit(event: ChatEvent) {
        viewModelScope.launch { _events.send(event) }
    }

    private fun openLatestUnreadPrivateChat() {
        try {
            val unreadKeys = state.getUnreadPrivateMessagesValue()
            if (unreadKeys.isEmpty()) return

            val me = state.getNicknameValue() ?: mesh.myPeerID
            val chats = state.getPrivateChatsValue()

            // Pick the latest incoming message among unread conversations
            var bestKey: String? = null
            var bestTime: Long = Long.MIN_VALUE

            unreadKeys.forEach { key ->
                val list = chats[key]
                if (!list.isNullOrEmpty()) {
                    // Prefer the latest incoming message (sender != me), fallback to last message
                    val latestIncoming = list.lastOrNull { it.sender != me }
                    val candidateTime = (latestIncoming ?: list.last()).timestamp.time
                    if (candidateTime > bestTime) {
                        bestTime = candidateTime
                        bestKey = key
                    }
                }
            }

            val targetKey = bestKey ?: unreadKeys.firstOrNull() ?: return

            val openPeer: String = if (targetKey.startsWith("nostr_")) {
                // Use the exact conversation key for geohash DMs and ensure DM subscription
                geohashSession.ensureGeohashDMSubscriptionForConversation(targetKey)
                targetKey
            } else {
                // Resolve to a canonical mesh peer if needed
                val canonical = com.bitchat.android.services.ConversationAliasResolver.resolveCanonicalPeerID(
                selectedPeerID = targetKey,
                connectedPeers = state.getConnectedPeersValue(),
                meshNoiseKeyForPeer = { pid -> mesh.getPeerInfo(pid)?.noisePublicKey },
                nostrPubHexForAlias = { alias -> com.bitchat.android.nostr.GeohashAliasRegistry.get(alias) },
                findNoiseKeyForNostr = { key -> com.bitchat.android.favorites.FavoritesPersistenceService.shared.findNoiseKey(key) }
                )
                canonical ?: targetKey
            }

            navigator.openPrivateChat(ContactDirectory.canonicalConversationId(openPeer))
        } catch (e: Exception) {
            Log.w(TAG, "openLatestUnreadPrivateChat failed: ${e.message}")
        }
    }
}
