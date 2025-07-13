package com.bitchat.android.mesh

import android.util.Log
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import kotlinx.coroutines.*
import java.util.*
import kotlin.collections.mutableSetOf

/**
 * Manages security aspects of the mesh network including duplicate detection,
 * replay attack protection, and key exchange handling
 * Extracted from BluetoothMeshService for better separation of concerns
 */
class SecurityManager(private val encryptionService: EncryptionService, private val myPeerID: String) {
    
    companion object {
        private const val TAG = "SecurityManager"
        private const val MESSAGE_TIMEOUT = 300000L // 5 minutes (same as iOS)
        private const val CLEANUP_INTERVAL = 300000L // 5 minutes
        private const val MAX_PROCESSED_MESSAGES = 10000
        private const val MAX_PROCESSED_KEY_EXCHANGES = 1000
    }
    
    // Security tracking
    private val processedMessages = Collections.synchronizedSet(mutableSetOf<String>())
    private val processedKeyExchanges = Collections.synchronizedSet(mutableSetOf<String>())
    private val messageTimestamps = Collections.synchronizedMap(mutableMapOf<String, Long>())
    
    // Delegate for callbacks
    var delegate: SecurityManagerDelegate? = null
    
    // Coroutines
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    init {
        startPeriodicCleanup()
    }
    
    /**
     * Validate packet security (timestamp, replay attacks, duplicates)
     */
    fun validatePacket(packet: BitchatPacket, peerID: String): Boolean {
        // Skip validation for our own packets
        if (peerID == myPeerID) {
            Log.d(TAG, "Skipping validation for our own packet")
            return false
        }
        
        // TTL check
        if (packet.ttl == 0u.toUByte()) {
            Log.d(TAG, "Dropping packet with TTL 0")
            return false
        }
        
        // Validate packet payload
        if (packet.payload.isEmpty()) {
            Log.d(TAG, "Dropping packet with empty payload")
            return false
        }
        
        // Replay attack protection (same 5-minute window as iOS)
        val currentTime = System.currentTimeMillis()
        val packetTime = packet.timestamp.toLong()
        val timeDiff = kotlin.math.abs(currentTime - packetTime)
        
        if (timeDiff > MESSAGE_TIMEOUT) {
            Log.d(TAG, "Dropping old packet from $peerID, time diff: ${timeDiff/1000}s")
            return false
        }
        
        // Duplicate detection
        val messageID = generateMessageID(packet, peerID)
        if (processedMessages.contains(messageID)) {
            Log.d(TAG, "Dropping duplicate packet: $messageID")
            return false
        }
        
        // Add to processed messages
        processedMessages.add(messageID)
        messageTimestamps[messageID] = currentTime
        
        Log.d(TAG, "Packet validation passed for $peerID, messageID: $messageID")
        return true
    }
    
    /**
     * Handle key exchange packet
     */
    suspend fun handleKeyExchange(packet: BitchatPacket, peerID: String): Boolean {
        if (peerID == myPeerID) return false
        
        if (packet.payload.isEmpty()) {
            Log.w(TAG, "Key exchange packet has empty payload")
            return false
        }
        
        // Prevent duplicate key exchange processing
        val exchangeKey = "$peerID-${packet.payload.sliceArray(0 until minOf(16, packet.payload.size)).contentHashCode()}"
        
        if (processedKeyExchanges.contains(exchangeKey)) {
            Log.d(TAG, "Already processed key exchange: $exchangeKey")
            return false
        }
        
        processedKeyExchanges.add(exchangeKey)
        
        try {
            // Process the key exchange
            encryptionService.addPeerPublicKey(peerID, packet.payload)
            
            Log.d(TAG, "Successfully processed key exchange from $peerID")
            
            // Notify delegate
            delegate?.onKeyExchangeCompleted(peerID, packet.payload)
            
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process key exchange from $peerID: ${e.message}")
            return false
        }
    }
    
    /**
     * Verify packet signature
     */
    fun verifySignature(packet: BitchatPacket, peerID: String): Boolean {
        return packet.signature?.let { signature ->
            try {
                val isValid = encryptionService.verify(signature, packet.payload, peerID)
                if (!isValid) {
                    Log.w(TAG, "Invalid signature for packet from $peerID")
                }
                isValid
            } catch (e: Exception) {
                Log.e(TAG, "Failed to verify signature from $peerID: ${e.message}")
                false
            }
        } ?: true // No signature means verification passes
    }
    
    /**
     * Sign packet payload
     */
    fun signPacket(payload: ByteArray): ByteArray? {
        return try {
            encryptionService.sign(payload)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sign packet: ${e.message}")
            null
        }
    }
    
    /**
     * Encrypt payload for specific peer
     */
    fun encryptForPeer(data: ByteArray, recipientPeerID: String): ByteArray? {
        return try {
            encryptionService.encrypt(data, recipientPeerID)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt for $recipientPeerID: ${e.message}")
            null
        }
    }
    
    /**
     * Decrypt payload from specific peer
     */
    fun decryptFromPeer(encryptedData: ByteArray, senderPeerID: String): ByteArray? {
        return try {
            encryptionService.decrypt(encryptedData, senderPeerID)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt from $senderPeerID: ${e.message}")
            null
        }
    }
    
    /**
     * Get combined public key data for key exchange
     */
    fun getCombinedPublicKeyData(): ByteArray {
        return encryptionService.getCombinedPublicKeyData()
    }
    
    /**
     * Generate message ID for duplicate detection
     */
    private fun generateMessageID(packet: BitchatPacket, peerID: String): String {
        return when (MessageType.fromValue(packet.type)) {
            MessageType.FRAGMENT_START, MessageType.FRAGMENT_CONTINUE, MessageType.FRAGMENT_END -> {
                // For fragments, include the payload hash to distinguish different fragments
                "${packet.timestamp}-$peerID-${packet.type}-${packet.payload.contentHashCode()}"
            }
            else -> {
                // For other messages, use a truncated payload hash
                val payloadHash = packet.payload.sliceArray(0 until minOf(64, packet.payload.size)).contentHashCode()
                "${packet.timestamp}-$peerID-$payloadHash"
            }
        }
    }
    
    /**
     * Check if we have encryption keys for a peer
     */
    fun hasKeysForPeer(peerID: String): Boolean {
        // This would need to be implemented in EncryptionService
        // For now, we'll assume we have keys if we processed a key exchange
        return processedKeyExchanges.any { it.startsWith("$peerID-") }
    }
    
    /**
     * Get debug information
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Security Manager Debug Info ===")
            appendLine("Processed Messages: ${processedMessages.size}")
            appendLine("Processed Key Exchanges: ${processedKeyExchanges.size}")
            appendLine("Message Timestamps: ${messageTimestamps.size}")
            
            if (processedKeyExchanges.isNotEmpty()) {
                appendLine("Key Exchange History:")
                processedKeyExchanges.take(10).forEach { exchange ->
                    appendLine("  - $exchange")
                }
                if (processedKeyExchanges.size > 10) {
                    appendLine("  ... and ${processedKeyExchanges.size - 10} more")
                }
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
                cleanupOldData()
            }
        }
    }
    
    /**
     * Clean up old processed messages and timestamps
     */
    private fun cleanupOldData() {
        val cutoffTime = System.currentTimeMillis() - MESSAGE_TIMEOUT
        var removedCount = 0
        
        // Clean up old message timestamps and corresponding processed messages
        val messagesToRemove = messageTimestamps.entries.filter { (_, timestamp) ->
            timestamp < cutoffTime
        }.map { it.key }
        
        messagesToRemove.forEach { messageId ->
            messageTimestamps.remove(messageId)
            if (processedMessages.remove(messageId)) {
                removedCount++
            }
        }
        
        // Limit the size of processed messages set
        if (processedMessages.size > MAX_PROCESSED_MESSAGES) {
            val excess = processedMessages.size - MAX_PROCESSED_MESSAGES
            val toRemove = processedMessages.take(excess)
            processedMessages.removeAll(toRemove.toSet())
            removeFromMessageTimestamps(toRemove)
            removedCount += excess
        }
        
        // Limit the size of processed key exchanges set
        if (processedKeyExchanges.size > MAX_PROCESSED_KEY_EXCHANGES) {
            val excess = processedKeyExchanges.size - MAX_PROCESSED_KEY_EXCHANGES
            val toRemove = processedKeyExchanges.take(excess)
            processedKeyExchanges.removeAll(toRemove.toSet())
        }
        
        if (removedCount > 0) {
            Log.d(TAG, "Cleaned up $removedCount old processed messages")
        }
    }
    
    /**
     * Helper to remove entries from messageTimestamps
     */
    private fun removeFromMessageTimestamps(messageIds: List<String>) {
        messageIds.forEach { messageId ->
            messageTimestamps.remove(messageId)
        }
    }
    
    /**
     * Clear all security data
     */
    fun clearAllData() {
        processedMessages.clear()
        processedKeyExchanges.clear()
        messageTimestamps.clear()
    }
    
    /**
     * Shutdown the manager
     */
    fun shutdown() {
        managerScope.cancel()
        clearAllData()
    }
}

/**
 * Delegate interface for security manager callbacks
 */
interface SecurityManagerDelegate {
    fun onKeyExchangeCompleted(peerID: String, peerPublicKeyData: ByteArray)
}
