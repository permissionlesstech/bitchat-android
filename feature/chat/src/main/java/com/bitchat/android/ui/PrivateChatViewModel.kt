package com.bitchat.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitchat.android.features.voice.VoiceRecorder
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.nostr.GeohashAliasRegistry
import com.bitchat.android.nostr.GeohashConversationRegistry
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.ContactIdentityResolver
import com.bitchat.android.services.ConversationListPreferences
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One private conversation, for as long as its entry is on the stack.
 *
 * The chat starts when the ViewModel is created and ends in [onCleared],
 * which runs when the entry leaves the stack however it left, and not when
 * the Activity is recreated. Ending goes through the guarded end, so a chat
 * replaced by another does not end the one now on screen.
 *
 * [routeConversationID] is the id the route was opened with. The conversation
 * shown is that id canonicalized again whenever the selection changes, since
 * the resolvers move the selection when a conversation resolves to a contact.
 */
@HiltViewModel(assistedFactory = PrivateChatViewModel.Factory::class)
class PrivateChatViewModel @AssistedInject constructor(
    @Assisted private val routeConversationID: String,
    private val state: ChatState,
    private val sessionMesh: ChatSessionMesh,
    private val privateChatSession: PrivateChatSession,
    private val messageSender: MessageSender,
    private val composerMedia: ComposerMedia,
    private val contactFavorites: ContactFavorites,
    private val verificationHandler: VerificationHandler,
    private val geohashSession: GeohashSession,
    private val conversationListPreferences: ConversationListPreferences,
    private val wifiAwarePeers: WifiAwarePeers,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(routeConversationID: String): PrivateChatViewModel
    }

    val conversationID: StateFlow<String> = state.selectedPrivateChatPeer
        .map { ContactDirectory.canonicalConversationId(routeConversationID) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = ContactDirectory.canonicalConversationId(routeConversationID),
        )

    private val resolvers = object : PrivateChatResolvers {
        override fun contact(conversationID: String) = ContactDirectory.resolve(conversationID)

        override fun favoriteStatus(conversationID: String) = try {
            FavoritesPersistenceService.shared.getFavoriteStatus(conversationID)
        } catch (_: Exception) {
            null
        }

        override fun fingerprintDisplayName(conversationID: String) =
            verificationHandler.resolvePeerDisplayNameForFingerprint(conversationID)

        override fun geohashOf(conversationID: String) =
            GeohashConversationRegistry.get(conversationID)

        override fun nostrPubkeyOf(conversationID: String) =
            GeohashAliasRegistry.get(conversationID)

        override fun geohashDisplayName(nostrPubkeyHex: String, geohash: String) =
            geohashSession.displayNameForGeohashConversation(nostrPubkeyHex, geohash)

        override fun fingerprintFromContactConversationID(conversationID: String) =
            ContactIdentityResolver.fingerprintFromContactConversationId(conversationID)

        override fun verificationFingerprint(conversationID: String) =
            verificationHandler.getPeerFingerprintForDisplay(conversationID)

        override fun isFavorite(conversationID: String) = contactFavorites.isFavorite(conversationID)
    }

    // Rebuilt from the current values whenever an input changes, and seeded the
    // same way. The registries the resolvers read are not observable; they are
    // re-read on every change, as the screen's remember blocks re-read them.
    private fun uiStateNow() = privateChatUiStateFor(
        PrivateChatInputs(
            conversationID = conversationID.value,
            privateChats = state.privateChats.value,
            peerNicknames = state.peerNicknames.value,
            nickname = state.nickname.value,
            connectedPeers = state.connectedPeers.value,
            peerDirect = state.peerDirect.value,
            peerSessionStates = state.peerSessionStates.value,
            favoritePeers = state.favoritePeers.value,
            peerFavoritedUs = state.peerFavoritedUs.value,
            peerFingerprints = state.peerFingerprints.value,
            verifiedFingerprints = verificationHandler.verifiedFingerprints.value,
            wifiAwarePeerIDs = wifiAwarePeers.connected.value.keys,
        ),
        resolvers,
    )

    val uiState: StateFlow<PrivateChatUiState> = combine(
        listOf(
            conversationID, state.privateChats, state.peerNicknames, state.nickname,
            state.connectedPeers, state.peerDirect, state.peerSessionStates,
            state.favoritePeers, state.peerFavoritedUs, state.peerFingerprints,
            verificationHandler.verifiedFingerprints, wifiAwarePeers.connected,
        )
    ) { uiStateNow() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), uiStateNow())

    private val _events = Channel<PrivateChatEvent>(Channel.BUFFERED)
    val events: Flow<PrivateChatEvent> = _events.receiveAsFlow()

    val mesh: MeshService
        get() = sessionMesh.unified

    init {
        // Once per conversation id, which changes only when it resolves to a
        // contact; starting again then is what the rewritten sheet peer did.
        viewModelScope.launch {
            var started: String? = null
            conversationID.collect { id ->
                if (id != started) {
                    started = id
                    privateChatSession.start(id)
                }
            }
        }
    }

    override fun onCleared() {
        privateChatSession.end(routeConversationID)
    }

    fun onAction(action: PrivateChatAction) {
        val conversation = conversationID.value
        when (action) {
            is PrivateChatAction.ComposerTextChanged ->
                conversationListPreferences.saveComposerDraft(conversation, action.text)
            is PrivateChatAction.Send -> messageSender.send(action.content) { accepted ->
                if (accepted) {
                    conversationListPreferences.saveComposerDraft(conversation, "")
                    viewModelScope.launch { _events.send(PrivateChatEvent.MessageSent) }
                }
            }
            is PrivateChatAction.SendVoiceNote ->
                composerMedia.sendVoiceNote(action.peerID, action.channel, action.path)
            is PrivateChatAction.SendImageNote ->
                composerMedia.sendImageNote(action.peerID, action.channel, action.path)
            is PrivateChatAction.SendFileNote ->
                composerMedia.sendFileNote(action.peerID, action.channel, action.path)
            is PrivateChatAction.CancelMediaSend -> composerMedia.cancelMediaSend(action.messageID)
            PrivateChatAction.ToggleFavorite -> contactFavorites.toggle(conversation)
        }
    }

    fun createVoiceRecorder(peerID: String?, channel: String?): VoiceRecorder =
        composerMedia.createVoiceRecorder(peerID, channel)

    /** The saved composer draft of [conversationID], read when the composer is created. */
    fun draft(conversationID: String): String =
        conversationListPreferences.composerDraft(conversationID)
}
