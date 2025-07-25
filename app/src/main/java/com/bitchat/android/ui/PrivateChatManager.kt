package com.bitchat.android.ui

import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.mesh.PeerFingerprintManager
import java.util.*
import android.util.Log

/**
 * Handles private chat functionality including peer management and blocking
 * Now uses centralized PeerFingerprintManager for all fingerprint operations
 */
class PrivateChatManager(
    private val state: ChatState,
    private val messageManager: MessageManager,
    private val dataManager: DataManager
) {
    
    companion object {
        private const val TAG = "PrivateChatManager"
    }
    
    // Use centralized fingerprint management - NO LOCAL STORAGE
    private val fingerprintManager = PeerFingerprintManager.getInstance()
    
    // Track received private messages that need read receipts
    private val unreadReceivedMessages = mutableMapOf<String, MutableList<BitchatMessage>>()
    
    // MARK: - Private Chat Lifecycle
    
    fun startPrivateChat(peerID: String, meshService: Any): Boolean {
        if (isPeerBlocked(peerID)) {
            val peerNickname = getPeerNickname(peerID, meshService)
            val systemMessage = BitchatMessage(
                sender = "system",
                content = "cannot start chat with $peerNickname: user is blocked.",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
            return false
        }
        
        state.setSelectedPrivateChatPeer(peerID)
        
        // Clear unread
        messageManager.clearPrivateUnreadMessages(peerID)
        
        // Initialize chat if needed
        messageManager.initializePrivateChat(peerID)
        
        // Send read receipts for all unread messages from this peer
        sendReadReceiptsForPeer(peerID, meshService)
        
        return true
    }
    
    fun endPrivateChat() {
        state.setSelectedPrivateChatPeer(null)
    }
    
    fun sendPrivateMessage(
        content: String,
        peerID: String,
        recipientNickname: String?,
        senderNickname: String?,
        myPeerID: String,
        onSendMessage: (String, String, String, String) -> Unit
    ): Boolean {
        if (isPeerBlocked(peerID)) {
            val systemMessage = BitchatMessage(
                sender = "system",
                content = "cannot send message to $recipientNickname: user is blocked.",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
            return false
        }
        
        val message = BitchatMessage(
            sender = senderNickname ?: myPeerID,
            content = content,
            timestamp = Date(),
            isRelay = false,
            isPrivate = true,
            recipientNickname = recipientNickname,
            senderPeerID = myPeerID,
            deliveryStatus = DeliveryStatus.Sending
        )
        
        messageManager.addPrivateMessage(peerID, message)
        onSendMessage(content, peerID, recipientNickname ?: "", message.id)
        
        return true
    }
    
    // MARK: - Peer Management
    
    fun isPeerBlocked(peerID: String): Boolean {
        val fingerprint = fingerprintManager.getFingerprintForPeer(peerID)
        return fingerprint != null && dataManager.isUserBlocked(fingerprint)
    }
    
    fun toggleFavorite(peerID: String) {
        val fingerprint = fingerprintManager.getFingerprintForPeer(peerID) ?: return
        
        Log.d(TAG, "toggleFavorite called for peerID: $peerID, fingerprint: $fingerprint")
        
        val wasFavorite = dataManager.isFavorite(fingerprint)
        Log.d(TAG, "Current favorite status: $wasFavorite")
        
        val currentFavorites = state.getFavoritePeersValue()
        Log.d(TAG, "Current UI state favorites: $currentFavorites")
        
        if (wasFavorite) {
            dataManager.removeFavorite(fingerprint)
            Log.d(TAG, "Removed from favorites: $fingerprint")
        } else {
            dataManager.addFavorite(fingerprint)
            Log.d(TAG, "Added to favorites: $fingerprint")
        }
        
        // Always update state to trigger UI refresh - create new set to ensure change detection
        val newFavorites = dataManager.favoritePeers.toSet()
        state.setFavoritePeers(newFavorites)
        
        Log.d(TAG, "Force updated favorite peers state. New favorites: $newFavorites")
        Log.d(TAG, "All peer fingerprints: ${fingerprintManager.getAllPeerFingerprints()}")
    }
    
    fun isFavorite(peerID: String): Boolean {
        val fingerprint = fingerprintManager.getFingerprintForPeer(peerID) ?: return false
        val isFav = dataManager.isFavorite(fingerprint)
        Log.d(TAG, "isFavorite check: peerID=$peerID, fingerprint=$fingerprint, result=$isFav")
        return isFav
    }
    
    fun getPeerFingerprint(peerID: String): String? {
        return fingerprintManager.getFingerprintForPeer(peerID)
    }
    
    fun getPeerFingerprints(): Map<String, String> {
        return fingerprintManager.getAllPeerFingerprints()
    }
    
    // MARK: - Block/Unblock Operations
    
    fun blockPeer(peerID: String, meshService: Any): Boolean {
        val fingerprint = fingerprintManager.getFingerprintForPeer(peerID)
        if (fingerprint != null) {
            dataManager.addBlockedUser(fingerprint)
            
            val peerNickname = getPeerNickname(peerID, meshService)
            val systemMessage = BitchatMessage(
                sender = "system",
                content = "blocked user $peerNickname",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
            
            // End private chat if currently in one with this peer
            if (state.getSelectedPrivateChatPeerValue() == peerID) {
                endPrivateChat()
            }
            
            return true
        }
        return false
    }
    
    fun unblockPeer(peerID: String, meshService: Any): Boolean {
        val fingerprint = fingerprintManager.getFingerprintForPeer(peerID)
        if (fingerprint != null && dataManager.isUserBlocked(fingerprint)) {
            dataManager.removeBlockedUser(fingerprint)
            
            val peerNickname = getPeerNickname(peerID, meshService)
            val systemMessage = BitchatMessage(
                sender = "system",
                content = "unblocked user $peerNickname",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
            return true
        }
        return false
    }
    
    fun blockPeerByNickname(targetName: String, meshService: Any): Boolean {
        val peerID = getPeerIDForNickname(targetName, meshService)
        
        if (peerID != null) {
            return blockPeer(peerID, meshService)
        } else {
            val systemMessage = BitchatMessage(
                sender = "system",
                content = "user '$targetName' not found",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
            return false
        }
    }
    
    fun unblockPeerByNickname(targetName: String, meshService: Any): Boolean {
        val peerID = getPeerIDForNickname(targetName, meshService)
        
        if (peerID != null) {
            val fingerprint = fingerprintManager.getFingerprintForPeer(peerID)
            if (fingerprint != null && dataManager.isUserBlocked(fingerprint)) {
                return unblockPeer(peerID, meshService)
            } else {
                val systemMessage = BitchatMessage(
                    sender = "system",
                    content = "user '$targetName' is not blocked",
                    timestamp = Date(),
                    isRelay = false
                )
                messageManager.addMessage(systemMessage)
                return false
            }
        } else {
            val systemMessage = BitchatMessage(
                sender = "system",
                content = "user '$targetName' not found",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
            return false
        }
    }
    
    fun listBlockedUsers(): String {
        val blockedCount = dataManager.blockedUsers.size
        return if (blockedCount == 0) {
            "no blocked users"
        } else {
            "blocked users: $blockedCount fingerprints"
        }
    }
    
    // MARK: - Message Handling
    
    fun handleIncomingPrivateMessage(message: BitchatMessage) {
        message.senderPeerID?.let { senderPeerID ->
            if (!isPeerBlocked(senderPeerID)) {
                // Add to private messages
                messageManager.addPrivateMessage(senderPeerID, message)
                
                // Track as unread for read receipt purposes
                val unreadList = unreadReceivedMessages.getOrPut(senderPeerID) { mutableListOf() }
                unreadList.add(message)
                
                Log.d(TAG, "Added received message ${message.id} from $senderPeerID to unread list (${unreadList.size} unread)")
            }
        }
    }
    
    /**
     * Send read receipts for all unread messages from a specific peer
     * Called when the user focuses on a private chat
     */
    fun sendReadReceiptsForPeer(peerID: String, meshService: Any) {
        val unreadList = unreadReceivedMessages[peerID]
        if (unreadList.isNullOrEmpty()) {
            Log.d(TAG, "No unread messages to send read receipts for peer $peerID")
            return
        }
        
        Log.d(TAG, "Sending read receipts for ${unreadList.size} unread messages from $peerID")
        
        // Send read receipt for each unread message
        unreadList.forEach { message ->
            try {
                val method = meshService::class.java.getDeclaredMethod("sendReadReceipt", String::class.java, String::class.java, String::class.java)
                val myNickname = state.getNicknameValue() ?: "unknown"
                method.invoke(meshService, message.id, peerID, myNickname)
                Log.d(TAG, "Sent read receipt for message ${message.id} to $peerID")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send read receipt for message ${message.id}: ${e.message}")
            }
        }
        
        // Clear the unread list since we've sent read receipts
        unreadReceivedMessages.remove(peerID)
    }
    
    fun cleanupDisconnectedPeer(peerID: String) {
        // End private chat if peer disconnected
        if (state.getSelectedPrivateChatPeerValue() == peerID) {
            endPrivateChat()
        }
        
        // Clean up unread messages for disconnected peer
        unreadReceivedMessages.remove(peerID)
        Log.d(TAG, "Cleaned up unread messages for disconnected peer $peerID")
    }
    
    // MARK: - Utility Functions
    
    private fun getPeerIDForNickname(nickname: String, meshService: Any): String? {
        // This would need to access the mesh service to get peer nicknames
        // For now, we'll assume the mesh service provides a way to get this mapping
        return try {
            val method = meshService::class.java.getDeclaredMethod("getPeerNicknames")
            val peerNicknames = method.invoke(meshService) as? Map<String, String>
            peerNicknames?.entries?.find { it.value == nickname }?.key
        } catch (e: Exception) {
            null
        }
    }
    
    private fun getPeerNickname(peerID: String, meshService: Any): String {
        return try {
            val method = meshService::class.java.getDeclaredMethod("getPeerNicknames")
            val peerNicknames = method.invoke(meshService) as? Map<String, String>
            peerNicknames?.get(peerID) ?: peerID
        } catch (e: Exception) {
            peerID
        }
    }
    
    // MARK: - Emergency Clear
    
    fun clearAllPrivateChats() {
        state.setSelectedPrivateChatPeer(null)
        state.setUnreadPrivateMessages(emptySet())
        
        // Clear unread messages tracking
        unreadReceivedMessages.clear()
        
        // Clear fingerprints via centralized manager (only if needed for emergency clear)
        // Note: This will be handled by the parent PeerManager.clearAllPeers()
    }
    
    // MARK: - Public Getters
    
    fun getAllPeerFingerprints(): Map<String, String> {
        return fingerprintManager.getAllPeerFingerprints()
    }
}
