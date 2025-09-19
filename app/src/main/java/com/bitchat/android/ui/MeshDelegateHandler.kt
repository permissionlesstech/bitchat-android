package com.bitchat.android.ui

import com.bitchat.android.mesh.BluetoothMeshDelegate
import com.bitchat.android.ui.NotificationTextUtils
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Date

/**
 * Handles all BluetoothMeshDelegate callbacks and routes them to appropriate managers
 */
class MeshDelegateHandler(
    private val state: ChatState,
    private val messageManager: MessageManager,
    private val channelManager: ChannelManager,
    private val privateChatManager: PrivateChatManager,
    private val notificationManager: NotificationManager,
    private val coroutineScope: CoroutineScope,
    private val onHapticFeedback: () -> Unit,
    private val getMyPeerID: () -> String,
    private val getMeshService: () -> BluetoothMeshService
) : BluetoothMeshDelegate {

    override fun didReceiveMessage(message: BitchatMessage) {
        coroutineScope.launch {
            // FIXED: Deduplicate messages from dual connection paths
            val messageKey = messageManager.generateMessageKey(message)
            if (messageManager.isMessageProcessed(messageKey)) {
                return@launch // Duplicate message, ignore
            }
            messageManager.markMessageProcessed(messageKey)
            
            // Check if sender is blocked
            message.senderPeerID?.let { senderPeerID ->
                if (privateChatManager.isPeerBlocked(senderPeerID)) {
                    return@launch
                }
            }
            
            // Trigger haptic feedback
            onHapticFeedback()

            if (message.isPrivate) {
                // Private message
                privateChatManager.handleIncomingPrivateMessage(message)
                
                // Reactive read receipts: Send immediately if user is currently viewing this chat
                message.senderPeerID?.let { senderPeerID ->
                    sendReadReceiptIfFocused(senderPeerID)
                }
                
                // Show notification with enhanced information - now includes senderPeerID 
                message.senderPeerID?.let { senderPeerID ->
                    // Use nickname if available, fall back to sender or senderPeerID
                    val senderNickname = message.sender.takeIf { it != senderPeerID } ?: senderPeerID
                    val preview = NotificationTextUtils.buildPrivateMessagePreview(message)
                    notificationManager.showPrivateMessageNotification(
                        senderPeerID = senderPeerID,
                        senderNickname = senderNickname,
                        messageContent = preview
                    )
                }
            } else if (message.channel != null) {
                // Channel message
                if (state.getJoinedChannelsValue().contains(message.channel)) {
                    channelManager.addChannelMessage(message.channel, message, message.senderPeerID)
                }
            } else {
                // Public mesh message - always store to preserve message history
                messageManager.addMessage(message)

                // Check for mentions in mesh chat
                checkAndTriggerMeshMentionNotification(message)
            }
            
            // Periodic cleanup
            if (messageManager.isMessageProcessed("cleanup_check_${System.currentTimeMillis()/30000}")) {
                messageManager.cleanupDeduplicationCaches()
            }
        }
    }
    
    override fun didUpdatePeerList(peers: List<String>) {
        coroutineScope.launch {
            state.setConnectedPeers(peers)
            state.setIsConnected(peers.isNotEmpty())
            notificationManager.showActiveUserNotification(peers)
            // Flush router outbox for any peers that just connected (and their noiseHex aliases)
            runCatching { com.bitchat.android.services.MessageRouter.tryGetInstance()?.onPeersUpdated(peers) }

            // Clean up channel members who disconnected
            channelManager.cleanupDisconnectedMembers(peers, getMyPeerID())

            // Handle chat view migration based on current selection and new peer list
            state.getSelectedPrivateChatPeerValue()?.let { currentPeer ->
                val isNostrAlias = currentPeer.startsWith("nostr_")
                val isNoiseHex = currentPeer.length == 64 && currentPeer.matches(Regex("^[0-9a-fA-F]+$"))
                val isMeshEphemeral = currentPeer.length == 16 && currentPeer.matches(Regex("^[0-9a-fA-F]+$"))

                if (isNostrAlias || isNoiseHex) {
                    // Reverse case: Nostr/offline chat is open, and peer may have come online on mesh.
                    // Resolve canonical target (prefer connected mesh peer if available)
                    val canonical = com.bitchat.android.services.ConversationAliasResolver.resolveCanonicalPeerID(
                        selectedPeerID = currentPeer,
                        connectedPeers = peers,
                        meshNoiseKeyForPeer = { pid -> getPeerInfo(pid)?.noisePublicKey },
                        meshHasPeer = { pid -> peers.contains(pid) },
                        nostrPubHexForAlias = { alias ->
                            // Use GeohashAliasRegistry for geohash aliases, but for mesh favorites, derive from favorites mapping
                            if (com.bitchat.android.nostr.GeohashAliasRegistry.contains(alias)) {
                                com.bitchat.android.nostr.GeohashAliasRegistry.get(alias)
                            } else {
                                // Best-effort: derive pub hex from favorites mapping for mesh nostr_ aliases
                                val prefix = alias.removePrefix("nostr_")
                                val favs = try { com.bitchat.android.favorites.FavoritesPersistenceService.shared.getOurFavorites() } catch (_: Exception) { emptyList() }
                                favs.firstNotNullOfOrNull { rel ->
                                    rel.peerNostrPublicKey?.let { s ->
                                        runCatching { com.bitchat.android.nostr.Bech32.decode(s) }.getOrNull()?.let { dec ->
                                            if (dec.first == "npub") dec.second.joinToString("") { b -> "%02x".format(b) } else null
                                        }
                                    }
                                }?.takeIf { it.startsWith(prefix, ignoreCase = true) }
                            }
                        },
                        findNoiseKeyForNostr = { key -> com.bitchat.android.favorites.FavoritesPersistenceService.shared.findNoiseKey(key) }
                    )
                    if (canonical != currentPeer) {
                        // Merge conversations and switch selection to the live mesh peer (or noiseHex)
                        com.bitchat.android.services.ConversationAliasResolver.unifyChatsIntoPeer(state, canonical, listOf(currentPeer))
                        state.setSelectedPrivateChatPeer(canonical)
                    }
                } else if (isMeshEphemeral && !peers.contains(currentPeer)) {
                    // Forward case: Mesh chat lost connection. If mutual favorite exists, migrate to Nostr (noiseHex)
                    val favoriteRel = try {
                        val info = getPeerInfo(currentPeer)
                        val noiseKey = info?.noisePublicKey
                        if (noiseKey != null) {
                            com.bitchat.android.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(noiseKey)
                        } else null
                    } catch (_: Exception) { null }

                    if (favoriteRel?.isMutual == true) {
                        val noiseHex = favoriteRel.peerNoisePublicKey.joinToString("") { b -> "%02x".format(b) }
                        if (noiseHex != currentPeer) {
                            com.bitchat.android.services.ConversationAliasResolver.unifyChatsIntoPeer(
                                state = state,
                                targetPeerID = noiseHex,
                                keysToMerge = listOf(currentPeer)
                            )
                            state.setSelectedPrivateChatPeer(noiseHex)
                        }
                    } else {
                        privateChatManager.cleanupDisconnectedPeer(currentPeer)
                    }
                }
            }

            // Global unification: for each connected peer, merge any offline/stable conversations
            // (noiseHex or nostr_<pub16>) into the connected peer's chat so there is only one chat per identity.
            peers.forEach { pid ->
                try {
                    val info = getPeerInfo(pid)
                    val noiseKey = info?.noisePublicKey ?: return@forEach
                    val noiseHex = noiseKey.joinToString("") { b -> "%02x".format(b) }

                    // Derive temp nostr key from favorites npub
                    val npub = com.bitchat.android.favorites.FavoritesPersistenceService.shared.findNostrPubkey(noiseKey)
                    val tempNostrKey: String? = try {
                        if (npub != null) {
                            val (hrp, data) = com.bitchat.android.nostr.Bech32.decode(npub)
                            if (hrp == "npub") "nostr_${data.joinToString("") { b -> "%02x".format(b) }.take(16)}" else null
                        } else null
                    } catch (_: Exception) { null }

                    unifyChatsIntoPeer(pid, listOfNotNull(noiseHex, tempNostrKey))
                } catch (_: Exception) { }
            }
        }
    }

    /**
     * Merge any chats stored under the given keys into the connected peer's chat entry.
     */
    private fun unifyChatsIntoPeer(targetPeerID: String, keysToMerge: List<String>) {
        com.bitchat.android.services.ConversationAliasResolver.unifyChatsIntoPeer(state, targetPeerID, keysToMerge)
    }

    override fun didReceiveChannelLeave(channel: String, fromPeer: String) {
        coroutineScope.launch {
            channelManager.removeChannelMember(channel, fromPeer)
        }
    }
    
    override fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String) {
        coroutineScope.launch {
            messageManager.updateMessageDeliveryStatus(messageID, DeliveryStatus.Delivered(recipientPeerID, Date()))
        }
    }
    
    override fun didReceiveReadReceipt(messageID: String, recipientPeerID: String) {
        coroutineScope.launch {
            messageManager.updateMessageDeliveryStatus(messageID, DeliveryStatus.Read(recipientPeerID, Date()))
        }
    }
    
    override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? {
        return channelManager.decryptChannelMessage(encryptedContent, channel)
    }
    
    override fun getNickname(): String? = state.getNicknameValue()
    
    override fun isFavorite(peerID: String): Boolean {
        return privateChatManager.isFavorite(peerID)
    }
    
    /**
     * Check for mentions in mesh messages and trigger notifications
     */
    private fun checkAndTriggerMeshMentionNotification(message: BitchatMessage) {
        try {
            // Get user's current nickname
            val currentNickname = state.getNicknameValue()
            if (currentNickname.isNullOrEmpty()) {
                return
            }

            // Check if this message mentions the current user using @username format
            val isMention = checkForMeshMention(message.content, currentNickname)

            if (isMention) {
                android.util.Log.d("MeshDelegateHandler", "🔔 Triggering mesh mention notification from ${message.sender}")

                notificationManager.showMeshMentionNotification(
                    senderNickname = message.sender,
                    messageContent = message.content,
                    senderPeerID = message.senderPeerID
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("MeshDelegateHandler", "Error checking mesh mentions: ${e.message}")
        }
    }

    /**
     * Check if the content mentions the current user with @username format (simple, no hash suffix)
     */
    private fun checkForMeshMention(content: String, currentNickname: String): Boolean {
        // Simple mention pattern for mesh: @username (no hash suffix like geohash)
        val mentionPattern = "@([\\p{L}0-9_]+)".toRegex()

        return mentionPattern.findAll(content).any { match ->
            val mentionedUsername = match.groupValues[1]
            // Direct comparison for mesh mentions (no hash suffix to remove)
            mentionedUsername.equals(currentNickname, ignoreCase = true)
        }
    }

    /**
     * Send read receipts reactively based on UI focus state.
     * Uses same logic as notification system - send read receipt if user is currently
     * viewing the private chat with this sender AND app is in foreground.
     */
    private fun sendReadReceiptIfFocused(senderPeerID: String) {
        // Get notification manager's focus state (mirror the notification logic)
        val isAppInBackground = notificationManager.getAppBackgroundState()
        val currentPrivateChatPeer = notificationManager.getCurrentPrivateChatPeer()
        
        // Send read receipt if user is currently focused on this specific chat
        val shouldSendReadReceipt = !isAppInBackground && currentPrivateChatPeer == senderPeerID
        
        if (shouldSendReadReceipt) {
            android.util.Log.d("MeshDelegateHandler", "Sending reactive read receipt for focused chat with $senderPeerID")
            privateChatManager.sendReadReceiptsForPeer(senderPeerID, getMeshService())
        } else {
            android.util.Log.d("MeshDelegateHandler", "Skipping read receipt - chat not focused (background: $isAppInBackground, current peer: $currentPrivateChatPeer, sender: $senderPeerID)")
        }
    }
    
    // registerPeerPublicKey REMOVED - fingerprints now handled centrally in PeerManager

    /**
     * Expose mesh peer info for components that need to resolve identities (e.g., Nostr mapping)
     */
    fun getPeerInfo(peerID: String): com.bitchat.android.mesh.PeerInfo? {
        return getMeshService().getPeerInfo(peerID)
    }

}
