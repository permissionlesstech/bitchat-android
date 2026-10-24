package com.bitchat.android.ui

import com.bitchat.android.di.ChatSessionScope
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.MessageRouter
import dagger.hilt.android.scopes.ActivityRetainedScoped
import java.util.Date
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Sends what the user typed, wherever the session is pointed: a command, a
 * private message, a geohash channel over Nostr, a mesh channel, or the
 * public mesh.
 *
 * Moved out of ChatViewModel unchanged, so every composer sends the same
 * way. The destination is read from ChatState at the moment of sending, not
 * passed in.
 */
@ActivityRetainedScoped
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
    private val messageRouterProvider: Provider<MessageRouter>,
    @ChatSessionScope private val scope: CoroutineScope,
) {

    private val mesh get() = sessionMesh.unified

    /**
     * Sends [content]. [onAccepted] reports whether it was taken, so the
     * composer knows to clear; a private message reports once it is stored.
     */
    fun send(
        content: String,
        onAccepted: (Boolean) -> Unit = {}
    ) {
        if (content.isEmpty()) {
            onAccepted(false)
            return
        }
        
        // Check for commands
        if (content.startsWith("/")) {
            val selectedLocationForCommand = state.selectedLocationChannel.value
            commandProcessor.processCommand(content, mesh, mesh.myPeerID, { messageContent, mentions, channel ->
                if (selectedLocationForCommand is com.bitchat.android.geohash.ChannelID.Location) {
                    // Route command-generated public messages via Nostr in geohash channels
                    geohashSession.sendGeohashMessage(
                        messageContent,
                        selectedLocationForCommand.channel,
                        mesh.myPeerID,
                        state.getNicknameValue()
                    )
                } else if (channel != null && channelManager.hasChannelKey(channel)) {
                    channelManager.sendEncryptedChannelMessage(
                        messageContent,
                        mentions,
                        channel,
                        state.getNicknameValue(),
                        mesh.myPeerID,
                        onEncryptedPayload = {
                            mesh.sendMessage(messageContent, mentions, channel)
                        },
                        onFallback = {
                            mesh.sendMessage(messageContent, mentions, channel)
                        }
                    )
                } else {
                    mesh.sendMessage(messageContent, mentions, channel)
                }
            })
            onAccepted(true)
            return
        }
        
        val mentions = messageManager.parseMentions(content, mesh.getPeerNicknames().values.toSet(), state.getNicknameValue())
        var selectedPeer = state.getSelectedPrivateChatPeerValue()
        val currentChannelValue = state.getCurrentChannelValue()
        
        if (selectedPeer != null) {
            // If the selected peer is a temporary Nostr alias or a noise-hex identity, resolve to a canonical target
            selectedPeer = ContactDirectory.canonicalConversationId(
                com.bitchat.android.services.ConversationAliasResolver.resolveCanonicalPeerID(
                selectedPeerID = selectedPeer,
                connectedPeers = state.getConnectedPeersValue(),
                meshNoiseKeyForPeer = { pid -> mesh.getPeerInfo(pid)?.noisePublicKey },
                nostrPubHexForAlias = { alias -> com.bitchat.android.nostr.GeohashAliasRegistry.get(alias) },
                findNoiseKeyForNostr = { key -> com.bitchat.android.favorites.FavoritesPersistenceService.shared.findNoiseKey(key) }
                )
            ).also { canonical ->
                if (canonical != state.getSelectedPrivateChatPeerValue()) {
                    privateChatManager.startPrivateChat(canonical, mesh)
                }
            }
            // Send private message
            val recipientNickname = nicknameForPeer(selectedPeer)
            val destination = selectedPeer
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
        } else {
            // Check if we're in a location channel
            val selectedLocationChannel = state.selectedLocationChannel.value
            if (selectedLocationChannel is com.bitchat.android.geohash.ChannelID.Location) {
                // Send to geohash channel via Nostr ephemeral event
                geohashSession.sendGeohashMessage(content, selectedLocationChannel.channel, mesh.myPeerID, state.getNicknameValue())
            } else {
                // Send public/channel message via mesh
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

                    // Check if encrypted channel
                    if (channelManager.hasChannelKey(currentChannelValue)) {
                        channelManager.sendEncryptedChannelMessage(
                            content,
                            mentions,
                            currentChannelValue,
                            state.getNicknameValue(),
                            mesh.myPeerID,
                            onEncryptedPayload = { encryptedData ->
                                mesh.sendMessage(content, mentions, currentChannelValue)
                            },
                            onFallback = {
                                mesh.sendMessage(content, mentions, currentChannelValue)
                            }
                        )
                    } else {
                        mesh.sendMessage(content, mentions, currentChannelValue)
                    }
                } else {
                    messageManager.addMessage(message)
                    mesh.sendMessage(content, mentions, null)
                }
            }
            onAccepted(true)
        }
    }

    private fun nicknameForPeer(peerID: String): String? {
        val contact = ContactDirectory.resolve(peerID)
        val meshPeerID = contact.meshPeerID ?: peerID
        return contact.displayName
            ?: state.peerNicknames.value[meshPeerID]
            ?: try { mesh.getPeerNicknames()[meshPeerID] } catch (_: Exception) { null }
    }
}
