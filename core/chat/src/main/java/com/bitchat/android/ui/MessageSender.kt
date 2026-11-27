package com.bitchat.android.ui

import com.bitchat.android.di.ChatSessionScoped
import com.bitchat.android.di.SessionRouter
import com.bitchat.android.nostr.GeohashAliasRegistry
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.di.ChatSessionScope
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.MessageRouter
import java.util.Date
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Sends what the user typed: from the main chat ([send]) a command, a geohash
 * channel over Nostr, a mesh channel or the public mesh; from a private chat
 * ([sendToConversation]) a command or a message to that chat.
 *
 * Moved out of ChatViewModel, so every composer sends the same way. The main
 * chat's destination is read from ChatState at the moment of sending, not
 * passed in.
 */
@ChatSessionScoped
class MessageSender @Inject constructor(
    private val state: ChatState,
    private val messageManager: MessageManager,
    private val channelManager: ChannelManager,
    private val privateChatManager: PrivateChatManager,
    private val commandProcessor: CommandProcessor,
    private val geohashSession: GeohashSession,
    private val sessionMesh: ChatSessionMesh,
    // Provider, not a cached instance: each get() re-points the router at the
    // current mesh, as it did in ChatViewModel.
    @SessionRouter private val messageRouterProvider: Provider<MessageRouter>,
    private val contactDirectory: ContactDirectory,
    private val favorites: Provider<FavoritesPersistenceService>,
    private val geohashAliases: GeohashAliasRegistry,
    @ChatSessionScope private val scope: CoroutineScope,
) {

    private val mesh get() = sessionMesh.unified

    /**
     * Sends [content] typed into the main chat, to what the main chat shows: the geohash
     * location channel, the open mesh channel or the public mesh. Text never goes to the
     * selected private chat, which only its own screen shows; that text goes through
     * [sendToConversation]. Commands still act on the selection (`/hug`, `/slap`, `/pay`,
     * `/clear`), which only a private chat that is open or leaving holds. [onAccepted]
     * reports whether it was taken, so the composer knows
     * to clear. [onSelectedOther] runs when a command (`/msg`) selected a different private
     * chat, which the caller must open so the selection is never hidden.
     */
    fun send(
        content: String,
        onSelectedOther: (conversationID: String) -> Unit = {},
        onAccepted: (Boolean) -> Unit = {}
    ) {
        if (content.isEmpty()) {
            onAccepted(false)
            return
        }
        
        // Check for commands
        if (content.startsWith("/")) {
            val selectedLocationForCommand = state.selectedLocationChannel.value
            val selectedBefore = state.getSelectedPrivateChatPeerValue()
            commandProcessor.processCommand(content, mesh, mesh.myPeerID, { messageContent, mentions, channel ->
                if (selectedLocationForCommand is com.bitchat.android.geohash.ChannelID.Location) {
                    // Route command-generated public messages via Nostr in geohash channels
                    geohashSession.sendGeohashMessage(
                        messageContent,
                        selectedLocationForCommand.channel,
                        mesh.myPeerID,
                        state.getNicknameValue()
                    )
                } else if (channel != null && channelManager.refuseSendIfPasswordProtected(channel)) {
                    // Refused with a visible notice; never falls back to plaintext.
                } else {
                    mesh.sendMessage(messageContent, mentions, channel)
                }
            })
            onAccepted(true)
            val selectedNow = state.getSelectedPrivateChatPeerValue()
            if (
                selectedNow != null &&
                (selectedBefore == null || !isSameConversation(selectedNow, selectedBefore))
            ) {
                onSelectedOther(selectedNow)
            }
            return
        }
        
        val mentions = messageManager.parseMentions(content, mesh.getPeerNicknames().values.toSet(), state.getNicknameValue())
        val currentChannelValue = state.getCurrentChannelValue()

        // Check if we're in a location channel
        val selectedLocationChannel = state.selectedLocationChannel.value
        if (selectedLocationChannel is com.bitchat.android.geohash.ChannelID.Location) {
            // Send to geohash channel via Nostr ephemeral event
            geohashSession.sendGeohashMessage(content, selectedLocationChannel.channel, mesh.myPeerID, state.getNicknameValue())
        } else {
            // Send public/channel message via mesh
            if (currentChannelValue != null &&
                channelManager.refuseSendIfPasswordProtected(currentChannelValue)
            ) {
                onAccepted(false)
                return
            }

            val message = BitchatMessage(
                sender = state.getNicknameValue() ?: mesh.myPeerID,
                content = content,
                timestamp = Date(),
                isRelay = false,
                senderPeerID = mesh.myPeerID,
                mentions = if (mentions.isNotEmpty()) mentions else null,
                channel = currentChannelValue
            )

            if (currentChannelValue != null) {
                channelManager.addChannelMessage(currentChannelValue, message, mesh.myPeerID)
                mesh.sendMessage(content, mentions, currentChannelValue)
            } else {
                messageManager.addMessage(message)
                mesh.sendMessage(content, mentions, null)
            }
        }
        onAccepted(true)
    }

    /**
     * Sends text typed into the private chat for [conversationID].
     *
     * That composer is addressed to this conversation, so unlike [send] this has no public
     * path and never uses a different selected chat: the selection lags the screen while a chat
     * starts on IO, and is never set for a blocked peer. The message reaches this conversation
     * or is refused. [onRefusedBlocked] runs when it was refused because the peer is blocked.
     * [onSelectedOther] runs when a command (`/msg`) selected a different conversation, which
     * the caller must present so the selection is never hidden behind this chat. [onLeft] runs
     * when a command (`/join`) left private chat altogether, which the caller must close: a chat
     * left open with no selection would refuse every later command.
     */
    fun sendToConversation(
        conversationID: String,
        content: String,
        onRefusedBlocked: () -> Unit = {},
        onSelectedOther: (conversationID: String) -> Unit = {},
        onLeft: () -> Unit = {},
        onAccepted: (Boolean) -> Unit = {},
    ) {
        if (content.isEmpty()) {
            onAccepted(false)
            return
        }

        if (content.startsWith("/")) {
            // Commands act on the selected chat, so they wait until that is this chat.
            val selectedPeer = state.getSelectedPrivateChatPeerValue()
            if (selectedPeer != null && isSameConversation(selectedPeer, conversationID)) {
                send(content, onSelectedOther, onAccepted)
                if (state.getSelectedPrivateChatPeerValue() == null) onLeft()
            } else {
                onAccepted(false)
            }
            return
        }

        val blocked = privateChatManager.isPeerBlocked(conversationID)
        sendPrivateConversationMessage(conversationID, content) { accepted ->
            onAccepted(accepted)
            if (blocked && !accepted) onRefusedBlocked()
        }
    }

    private fun isSameConversation(first: String, second: String): Boolean =
        contactDirectory.canonicalConversationId(first)
            .equals(contactDirectory.canonicalConversationId(second), ignoreCase = true)

    private fun sendPrivateConversationMessage(
        targetPeerID: String,
        content: String,
        onAccepted: (Boolean) -> Unit
    ) {
        // If the peer is a temporary Nostr alias or a noise-hex identity, resolve to a canonical target
        val destination = contactDirectory.canonicalConversationId(
            com.bitchat.android.services.ConversationAliasResolver.resolveCanonicalPeerID(
                selectedPeerID = targetPeerID,
                connectedPeers = state.getConnectedPeersValue(),
                meshNoiseKeyForPeer = { pid -> mesh.getPeerInfo(pid)?.noisePublicKey },
                nostrPubHexForAlias = { alias -> geohashAliases.get(alias) },
                findNoiseKeyForNostr = { key -> favorites.get().findNoiseKey(key) },
                contactDirectory = contactDirectory,
            )
        )
        if (
            destination != state.getSelectedPrivateChatPeerValue() &&
            !privateChatManager.isPeerBlocked(destination)
        ) {
            privateChatManager.startPrivateChat(destination, mesh)
        }
        val recipientNickname = nicknameForPeer(destination)
        scope.launch {
            val accepted = privateChatManager.sendPrivateMessageDurably(
                content,
                destination,
                recipientNickname,
                state.getNicknameValue(),
                mesh.myPeerID
            ) { messageContent, peerID, recipientNicknameParam, messageId ->
                val router = messageRouterProvider.get()
                val route = router.sendPrivate(
                    messageContent,
                    peerID,
                    recipientNicknameParam,
                    messageId
                )
                if (route == com.bitchat.android.services.MessageRouter.RouteResult.NOSTR) {
                    messageManager.updateMessageDeliveryStatus(
                        messageId,
                        com.bitchat.android.model.DeliveryStatus.Sent
                    )
                }
            }
            onAccepted(accepted)
        }
    }

    private fun nicknameForPeer(peerID: String): String? {
        val contact = contactDirectory.resolve(peerID)
        val meshPeerID = contact.meshPeerID ?: peerID
        return contact.displayName
            ?: state.peerNicknames.value[meshPeerID]
            ?: try { mesh.getPeerNicknames()[meshPeerID] } catch (_: Exception) { null }
    }
}
