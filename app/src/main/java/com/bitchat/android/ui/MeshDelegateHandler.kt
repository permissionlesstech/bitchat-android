package com.bitchat.android.ui

import androidx.lifecycle.LifecycleCoroutineScope
import com.bitchat.android.mesh.BluetoothMeshDelegate
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryAck
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.model.ReadReceipt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.*

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
    private val getMeshService: () -> Any
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
                    notificationManager.showPrivateMessageNotification(
                        senderPeerID = senderPeerID, 
                        senderNickname = senderNickname, 
                        messageContent = message.content
                    )
                }
            } else if (message.channel != null) {
                // Channel message
                if (state.getJoinedChannelsValue().contains(message.channel)) {
                    channelManager.addChannelMessage(message.channel, message, message.senderPeerID)
                }
            } else {
                // Public message
                messageManager.addMessage(message)
            }
            
            // Periodic cleanup
            if (messageManager.isMessageProcessed("cleanup_check_${System.currentTimeMillis()/30000}")) {
                messageManager.cleanupDeduplicationCaches()
            }
        }
    }
    
    override fun didConnectToPeer(peerID: String) {
        coroutineScope.launch {
            // FIXED: Deduplicate connection events from dual connection paths
            if (messageManager.isDuplicateSystemEvent("connect", peerID)) {
                return@launch
            }
            
            val systemMessage = BitchatMessage(
                sender = "system",
                content = "$peerID connected",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
        }
    }
    
    override fun didDisconnectFromPeer(peerID: String) {
        coroutineScope.launch {
            // FIXED: Deduplicate disconnection events from dual connection paths
            if (messageManager.isDuplicateSystemEvent("disconnect", peerID)) {
                return@launch
            }
            
            val systemMessage = BitchatMessage(
                sender = "system",
                content = "$peerID disconnected",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
        }
    }
    
    override fun didUpdatePeerList(peers: List<String>) {
        coroutineScope.launch {
            state.setConnectedPeers(peers)
            state.setIsConnected(peers.isNotEmpty())
            
            // Clean up channel members who disconnected
            channelManager.cleanupDisconnectedMembers(peers, getMyPeerID())
            
            // Exit private chat if peer disconnected
            state.getSelectedPrivateChatPeerValue()?.let { currentPeer ->
                if (!peers.contains(currentPeer)) {
                    privateChatManager.cleanupDisconnectedPeer(currentPeer)
                }
            }
        }
    }
    
    override fun didReceiveChannelLeave(channel: String, fromPeer: String) {
        coroutineScope.launch {
            channelManager.removeChannelMember(channel, fromPeer)
        }
    }
    
    override fun didReceiveDeliveryAck(ack: DeliveryAck) {
        coroutineScope.launch {
            messageManager.updateMessageDeliveryStatus(ack.originalMessageID, DeliveryStatus.Delivered(ack.recipientNickname, ack.timestamp))
        }
    }
    
    override fun didReceiveReadReceipt(receipt: ReadReceipt) {
        coroutineScope.launch {
            messageManager.updateMessageDeliveryStatus(receipt.originalMessageID, DeliveryStatus.Read(receipt.readerNickname, receipt.timestamp))
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
}
