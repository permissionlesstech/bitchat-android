package com.bitchat.android.ui

import com.bitchat.android.mesh.BluetoothMeshDelegate
import com.bitchat.android.ui.NotificationTextUtils
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.services.ContactDirectory
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
    private val getMeshService: () -> MeshService,
    private val markMessageReadLocally: (messageID: String) -> Unit = {}
) : BluetoothMeshDelegate {

    override fun didReceiveMessage(message: BitchatMessage) {
        coroutineScope.launch {
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
                if (message.sender == "system") {
                    // System notices (e.g. "x favorited you"): no unread badge, read receipt or push
                    privateChatManager.handleIncomingPrivateMessage(message, suppressUnread = true)
                } else {
                    // Private message
                    privateChatManager.handleIncomingPrivateMessage(message)

                    // Reactive read receipts: if chat is focused, send immediately for this message
                    message.senderPeerID?.let { senderPeerID ->
                        sendReadReceiptIfFocused(message)
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
                }
            } else if (message.channel != null) {
                // Channel message: AppStateStore is the source of truth for list; only manage unread
                if (state.getJoinedChannelsValue().contains(message.channel)) {
                    val channel = message.channel
                    val viewingClassic = state.getCurrentChannelValue() == channel
                    val viewingGeohash = try {
                        if (channel.startsWith("geo:")) {
                            val geo = channel.removePrefix("geo:")
                            val selected = state.selectedLocationChannel.value
                            selected is com.bitchat.android.geohash.ChannelID.Location && selected.channel.geohash.equals(geo, ignoreCase = true)
                        } else false
                    } catch (_: Exception) { false }
                    if (!viewingClassic && !viewingGeohash) {
                        val currentUnread = state.getUnreadChannelMessagesValue().toMutableMap()
                        currentUnread[channel] = (currentUnread[channel] ?: 0) + 1
                        state.setUnreadChannelMessages(currentUnread)
                    }
                }
            } else {
                // Public mesh message: AppStateStore is the source of truth; avoid double-adding to UI state
                // Still run mention detection/notifications
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
            processPeerUpdate(peers.distinct())
        }
    }

    private suspend fun processPeerUpdate(mergedPeers: List<String>) {
        state.setConnectedPeers(mergedPeers)
        state.setIsConnected(mergedPeers.isNotEmpty())
        
        // Flush router outbox for any peers that just connected (and their noiseHex aliases)
        runCatching { com.bitchat.android.services.MessageRouter.tryGetInstance()?.onPeersUpdated(mergedPeers) }

        // Clean up channel members who disconnected
        channelManager.cleanupDisconnectedMembers(mergedPeers, getMyPeerID())

        runCatching { com.bitchat.android.services.AppStateStore.canonicalizePrivateChats() }

        state.getSelectedPrivateChatPeerValue()?.let { currentPeer ->
            val canonical = ContactDirectory.canonicalConversationId(currentPeer)
            if (canonical != currentPeer) {
                com.bitchat.android.services.ConversationAliasResolver.unifyChatsIntoPeer(
                    state = state,
                    targetPeerID = canonical,
                    keysToMerge = ContactDirectory.aliasesForConversation(currentPeer).toList()
                )
                state.setSelectedPrivateChatPeer(canonical)
            }
        }

        state.getPrivateChatSheetPeerValue()?.let { sheetPeer ->
            val canonical = ContactDirectory.canonicalConversationId(sheetPeer)
            if (canonical != sheetPeer) {
                state.setPrivateChatSheetPeer(canonical)
            }
        }

        mergedPeers.forEach { pid ->
            try {
                val canonical = ContactDirectory.canonicalConversationId(pid)
                unifyChatsIntoPeer(canonical, ContactDirectory.aliasesForConversation(pid).toList())
            } catch (_: Exception) { }
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

    override fun didReceiveVerifyChallenge(peerID: String, payload: ByteArray, timestampMs: Long) {
        // Handled by ChatViewModel for verification flow
    }

    override fun didReceiveVerifyResponse(peerID: String, payload: ByteArray, timestampMs: Long) {
        // Handled by ChatViewModel for verification flow
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
    private fun sendReadReceiptIfFocused(message: BitchatMessage) {
        // Get notification manager's focus state (mirror the notification logic)
        val isAppInBackground = notificationManager.getAppBackgroundState()
        val currentPrivateChatPeer = notificationManager.getCurrentPrivateChatPeer()
        
        // Send read receipt if user is currently focused on this specific chat
        val senderPeerID = message.senderPeerID
        val senderConversationID = senderPeerID?.let { ContactDirectory.canonicalConversationId(it) }
        val focusedConversationID = currentPrivateChatPeer?.let { ContactDirectory.canonicalConversationId(it) }
        val shouldSendReadReceipt = !isAppInBackground &&
            senderConversationID != null &&
            focusedConversationID == senderConversationID

        if (shouldSendReadReceipt) {
            android.util.Log.d(
                "MeshDelegateHandler",
                "Sending reactive read receipt for focused chat with $senderConversationID (message=${message.id})"
            )
            // UI focus is the source of truth for local read state. Transport acceptance is a
            // separate fact and may remain retryable when the peer disconnects.
            try { markMessageReadLocally(message.id) } catch (_: Exception) { }

            val nickname = state.getNicknameValue().ifBlank { "unknown" }
            val mesh = getMeshService()
            try {
                val meshPeerID = ContactDirectory.resolve(senderConversationID).meshPeerID
                    ?: senderPeerID.takeIf {
                        com.bitchat.android.services.ContactIdentityResolver.isMeshPeerId(it)
                    }
                if (meshPeerID != null &&
                    mesh.getPeerInfo(meshPeerID)?.isConnected == true &&
                    mesh.hasEstablishedSession(meshPeerID)
                ) {
                    mesh.sendReadReceipt(message.id, meshPeerID, nickname)
                }
            } catch (_: Exception) { }

            // Ensure unread badge is cleared for this peer immediately.
            try {
                val current = state.getUnreadPrivateMessagesValue().toMutableSet()
                val changed = current.remove(senderPeerID) or current.remove(senderConversationID)
                if (changed) {
                    state.setUnreadPrivateMessages(current)
                }
            } catch (_: Exception) { }
        } else {
            android.util.Log.d("MeshDelegateHandler", "Skipping read receipt - chat not focused (background: $isAppInBackground, current peer: $currentPrivateChatPeer, sender: $senderPeerID)")
        }
    }

    /**
     * Expose mesh peer info for components that need to resolve identities (e.g., Nostr mapping)
     */
    fun getPeerInfo(peerID: String): com.bitchat.android.mesh.PeerInfo? {
        return getMeshService().getPeerInfo(peerID)
    }

}
