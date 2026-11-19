package com.bitchat.android.mesh

import android.util.Log
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages store-and-forward messaging for offline peers
 * Extracted from BluetoothMeshService for better separation of concerns
 */
class StoreForwardManager {
    
    companion object {
        private const val TAG = "StoreForwardManager"
        private const val MESSAGE_CACHE_TIMEOUT = com.bitchat.android.util.AppConstants.StoreForward.MESSAGE_CACHE_TIMEOUT_MS  // 12 hours for regular peers
        private const val MAX_CACHED_MESSAGES = com.bitchat.android.util.AppConstants.StoreForward.MAX_CACHED_MESSAGES  // For regular peers
        private const val MAX_CACHED_MESSAGES_FAVORITES = com.bitchat.android.util.AppConstants.StoreForward.MAX_CACHED_MESSAGES_FAVORITES  // For favorites
        private const val CLEANUP_INTERVAL = com.bitchat.android.util.AppConstants.StoreForward.CLEANUP_INTERVAL_MS // 10 minutes
    }
    
    /**
     * Data class for stored messages
     */
    private data class StoredMessage(
        val packet: BitchatPacket,
        val timestamp: Long,
        val messageID: String,
        val isForFavorite: Boolean
    )
    
    // Message storage
    private val messageCache = Collections.synchronizedList(mutableListOf<StoredMessage>())
    private val favoriteMessageQueue = ConcurrentHashMap<String, MutableList<StoredMessage>>()
    private val deliveredMessages = Collections.synchronizedSet(mutableSetOf<String>())
    private val cachedMessagesSentToPeer = Collections.synchronizedSet(mutableSetOf<String>())
    
    // Delegate for callbacks
    var delegate: StoreForwardManagerDelegate? = null
    
    // Coroutines
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    init {
        startPeriodicCleanup()
    }
    
    /**
     * Cache message for offline delivery
     */
    fun cacheMessage(packet: BitchatPacket, messageID: String) {
        // Skip certain message types (same as iOS)
        if (packet.type == MessageType.NOISE_HANDSHAKE.value ||
            packet.type == MessageType.NOISE_ENCRYPTED.value ||
            packet.type == MessageType.ANNOUNCE.value ||
            packet.type == MessageType.LEAVE.value) {
            Log.d(TAG, "Skipping cache for message type: ${packet.type}")
            return
        }
        
        // Don't cache broadcast messages
        if (packet.recipientID != null && packet.recipientID.contentEquals(SpecialRecipients.BROADCAST)) {
            Log.d(TAG, "Skipping cache for broadcast message")
            return
        }
        
        // Determine if this is for a favorite peer
        val recipientPeerID = packet.recipientID?.let { recipientID ->
            String(recipientID).replace("\u0000", "")
        }
        
        if (recipientPeerID.isNullOrEmpty()) {
            Log.w(TAG, "Cannot cache message without valid recipient")
            return
        }
        
        val isForFavorite = delegate?.isFavorite(recipientPeerID) ?: false
        
        val storedMessage = StoredMessage(
            packet = packet,
            timestamp = System.currentTimeMillis(),
            messageID = messageID,
            isForFavorite = isForFavorite
        )
        
        if (isForFavorite) {
            // Store in favorite queue
            if (!favoriteMessageQueue.containsKey(recipientPeerID)) {
                favoriteMessageQueue[recipientPeerID] = mutableListOf()
            }
            favoriteMessageQueue[recipientPeerID]?.add(storedMessage)
            
            // Limit favorite queue size
            if (favoriteMessageQueue[recipientPeerID]?.size ?: 0 > MAX_CACHED_MESSAGES_FAVORITES) {
                favoriteMessageQueue[recipientPeerID]?.removeAt(0)
            }
            
            Log.d(TAG, "Cached message for favorite peer $recipientPeerID (${favoriteMessageQueue[recipientPeerID]?.size} total)")
            
        } else {
            // Store in regular cache
            cleanupMessageCache()
            
            messageCache.add(storedMessage)
            
            // Limit cache size
            if (messageCache.size > MAX_CACHED_MESSAGES) {
                messageCache.removeAt(0)
            }
            
            Log.d(TAG, "Cached message for peer $recipientPeerID (${messageCache.size} total in cache)")
        }
    }
    
    /**
     * Send cached messages to peer when they come online
     */
    fun sendCachedMessages(peerID: String) {
        if (cachedMessagesSentToPeer.contains(peerID)) {
            Log.d(TAG, "Already sent cached messages to $peerID")
            return // Already sent cached messages to this peer
        }
        
        cachedMessagesSentToPeer.add(peerID)
        
        managerScope.launch {
            cleanupMessageCache()
            
            val messagesToSend = mutableListOf<StoredMessage>()
            
            // Check favorite queue
            favoriteMessageQueue[peerID]?.let { favoriteMessages ->
                val undeliveredFavorites = favoriteMessages.filter { !deliveredMessages.contains(it.messageID) }
                messagesToSend.addAll(undeliveredFavorites)
                favoriteMessageQueue.remove(peerID)
                Log.d(TAG, "Found ${undeliveredFavorites.size} cached favorite messages for $peerID")
            }
            
            // Filter regular cached messages for this recipient
            val recipientMessages = messageCache.filter { storedMessage ->
                !deliveredMessages.contains(storedMessage.messageID) &&
                storedMessage.packet.recipientID?.let { recipientID ->
                    String(recipientID).replace("\u0000", "") == peerID
                } == true
            }
            messagesToSend.addAll(recipientMessages)
            
            if (recipientMessages.isNotEmpty()) {
                Log.d(TAG, "Found ${recipientMessages.size} cached regular messages for $peerID")
            }
            
            // Sort by timestamp
            messagesToSend.sortBy { it.timestamp }
            
            if (messagesToSend.isNotEmpty()) {
                Log.i(TAG, "Sending ${messagesToSend.size} cached messages to $peerID")
            }
            
            // Mark as delivered
            val messageIDsToRemove = messagesToSend.map { it.messageID }
            deliveredMessages.addAll(messageIDsToRemove)
            
            // Send with delays to avoid overwhelming the connection
            messagesToSend.forEachIndexed { index, storedMessage ->
                delay(index * 10L) // 10ms between messages
                delegate?.sendPacket(storedMessage.packet)
            }
            
            // Remove sent messages from cache
            messageCache.removeAll { messageIDsToRemove.contains(it.messageID) }
            
            if (messagesToSend.isNotEmpty()) {
                Log.d(TAG, "Finished sending ${messagesToSend.size} cached messages to $peerID")
            }
        }
    }
    
    /**
     * Check if message should be cached for peer
     */
    fun shouldCacheForPeer(recipientPeerID: String): Boolean {
        // Check if recipient is offline and should cache for favorites
        val isOffline = !(delegate?.isPeerOnline(recipientPeerID) ?: false)
        val isRecipientFavorite = delegate?.isFavorite(recipientPeerID) ?: false
        
        return isOffline && isRecipientFavorite
    }
    
    /**
     * Mark message as delivered
     */
    fun markMessageAsDelivered(messageID: String) {
        deliveredMessages.add(messageID)
    }
    
    /**
     * Get cached message count for peer
     */
    fun getCachedMessageCount(peerID: String): Int {
        val favoriteCount = favoriteMessageQueue[peerID]?.size ?: 0
        val regularCount = messageCache.count { storedMessage ->
            storedMessage.packet.recipientID?.let { recipientID ->
                String(recipientID).replace("\u0000", "") == peerID
            } == true
        }
        return favoriteCount + regularCount
    }
    
    /**
     * Get debug information
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Store-Forward Manager Debug Info ===")
            appendLine("Regular Cache: ${messageCache.size}/${MAX_CACHED_MESSAGES}")
            appendLine("Favorite Queues: ${favoriteMessageQueue.size}")
            
            favoriteMessageQueue.forEach { (peerID, messages) ->
                appendLine("  - $peerID: ${messages.size} messages")
            }
            
            appendLine("Delivered Messages: ${deliveredMessages.size}")
            appendLine("Peers Sent Cache: ${cachedMessagesSentToPeer.size}")
            
            // Cache age analysis
            val now = System.currentTimeMillis()
            val regularCacheAges = messageCache.map { (now - it.timestamp) / 1000 }
            if (regularCacheAges.isNotEmpty()) {
                val avgAge = regularCacheAges.average().toInt()
                val maxAge = regularCacheAges.maxOrNull() ?: 0
                appendLine("Regular Cache Age: avg ${avgAge}s, max ${maxAge}s")
            }
        }
    }
    
    /**
     * Start periodic cleanup
     */
    private fun startPeriodicCleanup() {
        managerScope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL)
                cleanupMessageCache()
                cleanupDeliveredMessages()
            }
        }
    }
    
    /**
     * Clean up old cached messages (not for favorites)
     */
    private fun cleanupMessageCache() {
        val cutoffTime = System.currentTimeMillis() - MESSAGE_CACHE_TIMEOUT
        val sizeBefore = messageCache.size
        val removed = messageCache.removeAll { !it.isForFavorite && it.timestamp < cutoffTime }
        
        if (removed) {
            val removedCount = sizeBefore - messageCache.size
            Log.d(TAG, "Cleaned up $removedCount old cached messages")
        }
    }
    
    /**
     * Clean up delivered messages set (prevent memory leak)
     */
    private fun cleanupDeliveredMessages() {
        if (deliveredMessages.size > 1000) {
            Log.d(TAG, "Clearing delivered messages set (${deliveredMessages.size} entries)")
            deliveredMessages.clear()
        }
        
        if (cachedMessagesSentToPeer.size > 200) {
            Log.d(TAG, "Clearing cached messages sent tracking (${cachedMessagesSentToPeer.size} entries)")
            cachedMessagesSentToPeer.clear()
        }
    }
    
    /**
     * Clear all cached data
     */
    fun clearAllCache() {
        messageCache.clear()
        favoriteMessageQueue.clear()
        deliveredMessages.clear()
        cachedMessagesSentToPeer.clear()
        Log.d(TAG, "Cleared all cached message data")
    }
    
    /**
     * Force cleanup for testing
     */
    fun forceCleanup() {
        cleanupMessageCache()
        cleanupDeliveredMessages()
    }
    
    /**
     * Shutdown the manager
     */
    fun shutdown() {
        managerScope.cancel()
        clearAllCache()
    }
}

/**
 * Delegate interface for store-forward manager callbacks
 */
interface StoreForwardManagerDelegate {
    fun isFavorite(peerID: String): Boolean
    fun isPeerOnline(peerID: String): Boolean
    fun sendPacket(packet: BitchatPacket)
}
