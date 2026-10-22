package com.bitchat.android.ui

import androidx.compose.runtime.Immutable
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.model.BitchatMessage

/**
 * The chat screen's state, grouped by the part of the screen that reads it, so
 * each part is handed only its own group and a change to one does not
 * recompose the others.
 */
@Immutable
data class ChatUiState(
    val timeline: TimelineState = TimelineState(),
    val header: HeaderState = HeaderState(),
    val composer: ComposerState = ComposerState(),
    val dialogs: DialogState = DialogState(),
)

/** The conversation on screen and what its rows need to render. */
@Immutable
data class TimelineState(
    /** The messages of the conversation on screen: a channel, a geohash, or the mesh. */
    val messages: List<BitchatMessage> = emptyList(),
    /** Which conversation [messages] belongs to; keys scroll position and animation state. */
    val conversationKey: String = MESH_CONVERSATION_KEY,
    val currentChannel: String? = null,
    val selectedPrivatePeer: String? = null,
    val selectedLocationChannel: ChannelID? = null,
    val nickname: String = "",
    val connectedPeers: List<String> = emptyList(),
    val peerNicknames: Map<String, String> = emptyMap(),
    val geohashPeople: List<GeoPerson> = emptyList(),
)

@Immutable
data class HeaderState(
    /** Every connected mesh peer except this device. */
    val connectedPeers: List<String> = emptyList(),
    val joinedChannels: Set<String> = emptySet(),
    val unreadChannelMessages: Map<String, Int> = emptyMap(),
    val hasUnreadPrivateMessages: Boolean = false,
    val isConnected: Boolean = false,
    val selectedLocationChannel: ChannelID? = null,
    val geohashPeople: List<GeoPerson> = emptyList(),
)

@Immutable
data class ComposerState(
    val showCommandSuggestions: Boolean = false,
    val commandSuggestions: List<CommandSuggestion> = emptyList(),
    val showMentionSuggestions: Boolean = false,
    val mentionSuggestions: List<String> = emptyList(),
)

@Immutable
data class DialogState(
    val showPasswordPrompt: Boolean = false,
    val passwordPromptChannel: String? = null,
    val legacyPrivateMediaConsent: LegacyPrivateMediaConsentRequest? = null,
)

/** Everything the chat screen asks of its ViewModel. */
sealed interface ChatAction {
    /** The composer changed; saves the draft of [conversationID] and refreshes suggestions. */
    data class ComposerTextChanged(val text: String, val conversationID: String?) : ChatAction
    /** Sends [content]; [ChatEvent.MessageSent] follows once it is accepted. */
    data class Send(val content: String, val conversationID: String?) : ChatAction
    /** [ChatEvent.ReplaceComposerText] follows with the completed command. */
    data class SelectCommandSuggestion(val suggestion: CommandSuggestion) : ChatAction
    /** [ChatEvent.ReplaceComposerText] follows with [currentText] completed to the mention. */
    data class SelectMentionSuggestion(val nickname: String, val currentText: String) : ChatAction
    data class SendVoiceNote(val peerID: String?, val channel: String?, val path: String) : ChatAction
    data class SendImageNote(val peerID: String?, val channel: String?, val path: String) : ChatAction
    data class SendFileNote(val peerID: String?, val channel: String?, val path: String) : ChatAction
    data class CancelMediaSend(val messageID: String) : ChatAction
    data class ApproveLegacyPrivateMedia(val requestID: String) : ChatAction
    data class CancelLegacyPrivateMedia(val requestID: String) : ChatAction
    /** Joins the prompted channel; on success the prompt closes. */
    data class SubmitChannelPassword(val channel: String, val password: String) : ChatAction
    data object DismissPasswordPrompt : ChatAction
    data class SetNickname(val nickname: String) : ChatAction
    data class LeaveChannel(val channel: String) : ChatAction
    /** Returns from a channel to the location timeline. */
    data object ExitChannel : ChatAction
    data object OpenLatestUnreadPrivateChat : ChatAction
    data object PanicClear : ChatAction
}

/** One-shot effects on the chat screen's local UI state. */
sealed interface ChatEvent {
    /** A sent message was accepted: clear the composer and scroll to the bottom. */
    data object MessageSent : ChatEvent
    /** Replace the composer's text, with the cursor at its end. */
    data class ReplaceComposerText(val text: String) : ChatEvent
}

internal const val MESH_CONVERSATION_KEY = "mesh"

/**
 * The timeline for the current selection: an open channel wins, then a
 * geohash location channel, and otherwise the mesh.
 */
internal fun timelineStateOf(
    meshMessages: List<BitchatMessage>,
    channelMessages: Map<String, List<BitchatMessage>>,
    currentChannel: String?,
    selectedPrivatePeer: String?,
    selectedLocationChannel: ChannelID?,
    nickname: String,
    connectedPeers: List<String>,
    peerNicknames: Map<String, String>,
    geohashPeople: List<GeoPerson>,
): TimelineState {
    val (messages, conversationKey) = when {
        currentChannel != null ->
            (channelMessages[currentChannel] ?: emptyList()) to "channel:$currentChannel"
        selectedLocationChannel is ChannelID.Location -> {
            val geokey = "geo:${selectedLocationChannel.channel.geohash}"
            (channelMessages[geokey] ?: emptyList()) to geokey
        }
        else -> meshMessages to MESH_CONVERSATION_KEY
    }
    return TimelineState(
        messages = messages,
        conversationKey = conversationKey,
        currentChannel = currentChannel,
        selectedPrivatePeer = selectedPrivatePeer,
        selectedLocationChannel = selectedLocationChannel,
        nickname = nickname,
        connectedPeers = connectedPeers,
        peerNicknames = peerNicknames,
        geohashPeople = geohashPeople,
    )
}

internal fun headerStateOf(
    connectedPeers: List<String>,
    myPeerID: String,
    joinedChannels: Set<String>,
    unreadChannelMessages: Map<String, Int>,
    unreadPrivateMessages: Set<String>,
    isConnected: Boolean,
    selectedLocationChannel: ChannelID?,
    geohashPeople: List<GeoPerson>,
) = HeaderState(
    connectedPeers = connectedPeers.filter { it != myPeerID },
    joinedChannels = joinedChannels,
    unreadChannelMessages = unreadChannelMessages,
    hasUnreadPrivateMessages = unreadPrivateMessages.isNotEmpty(),
    isConnected = isConnected,
    selectedLocationChannel = selectedLocationChannel,
    geohashPeople = geohashPeople,
)
