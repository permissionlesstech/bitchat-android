package com.bitchat.android.mesh

import android.util.Log
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.util.toHexString
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
        private const val MESSAGE_TIMEOUT = com.bitchat.android.util.AppConstants.Security.MESSAGE_TIMEOUT_MS // 5 minutes (same as iOS)
        private const val CLEANUP_INTERVAL = com.bitchat.android.util.AppConstants.Security.CLEANUP_INTERVAL_MS // 5 minutes
        private const val MAX_PROCESSED_MESSAGES = com.bitchat.android.util.AppConstants.Security.MAX_PROCESSED_MESSAGES
        private const val MAX_PROCESSED_KEY_EXCHANGES = com.bitchat.android.util.AppConstants.Security.MAX_PROCESSED_KEY_EXCHANGES
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
     * Validate packet security (timestamp, replay attacks, duplicates, signatures)
     */
    fun validatePacket(packet: BitchatPacket, peerID: String): Boolean {
        // Skip validation for our own packets
        if (peerID == myPeerID) {
            Log.d(TAG, "Skipping validation for our own packet")
            return false
        }
        
        // Replay attack protection (same 5-minute window as iOS)
        val currentTime = System.currentTimeMillis()
        val messageType = MessageType.fromValue(packet.type)

        // Duplicate detection
        val messageID = generateMessageID(packet, peerID)
        if (messageType != MessageType.ANNOUNCE) {
            if (processedMessages.contains(messageID)) {
                Log.d(TAG, "Dropping duplicate packet: $messageID")
                return false
            }
            // Add to processed messages
            processedMessages.add(messageID)
            messageTimestamps[messageID] = currentTime
        } else {
            // Do not deduplicate ANNOUNCE at the security layer.
            // They are signed/idempotent and we need to ensure first-announce per-connection can bind.
        }
        
        // NEW: Signature verification logging (not rejecting yet)
        verifyPacketSignatureWithLogging(packet, peerID)
        
        Log.d(TAG, "Packet validation passed for $peerID, messageID: $messageID")
        return true
    }
    
    /**
     * Handle Noise handshake packet - SIMPLIFIED iOS-compatible version
     * Single handshake type with automatic response handling
     */
    suspend fun handleNoiseHandshake(routed: RoutedPacket): Boolean {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"

        // Skip handshakes not addressed to us
        if (packet.recipientID?.toHexString() != myPeerID) {
            Log.d(TAG, "Skipping handshake not addressed to us: $peerID")
            return false
        }
            
        // Skip our own handshake messages
        if (peerID == myPeerID) return false

        // If we already have an established session but the peer is initiating a new handshake,
        // drop the existing session so we can re-establish cleanly.
        var forcedRehandshake = false
        if (encryptionService.hasEstablishedSession(peerID)) {
            Log.d(TAG, "Received new Noise handshake from $peerID with an existing session. Dropping old session to re-handshake.")
            try {
                encryptionService.removePeer(peerID)
                forcedRehandshake = true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove existing Noise session for $peerID: ${e.message}")
            }
        }
        
        if (packet.payload.isEmpty()) {
            Log.w(TAG, "Noise handshake packet has empty payload")
            return false
        }
        
        // Prevent duplicate handshake processing
        val exchangeKey = "$peerID-${packet.payload.sliceArray(0 until minOf(16, packet.payload.size)).contentHashCode()}"
        
        if (!forcedRehandshake && processedKeyExchanges.contains(exchangeKey)) {
            Log.d(TAG, "Already processed handshake: $exchangeKey")
            return false
        }
        Log.d(TAG, "Processing Noise handshake from $peerID (${packet.payload.size} bytes)")
        processedKeyExchanges.add(exchangeKey)
        
        try {
            // Process the Noise handshake through the updated EncryptionService
            val response = encryptionService.processHandshakeMessage(packet.payload, peerID)
            
            if (response != null) {
                Log.d(TAG, "Successfully processed Noise handshake from $peerID, sending response")
                // Send handshake response through delegate
                delegate?.sendHandshakeResponse(peerID, response)
            }
            // Check if session is now established (handshake complete)
            if (encryptionService.hasEstablishedSession(peerID)) {
                Log.d(TAG, "✅ Noise handshake completed with $peerID")
                delegate?.onKeyExchangeCompleted(peerID, packet.payload)
            }
            return true

            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process Noise handshake from $peerID: ${e.message}")
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
            MessageType.FRAGMENT -> {
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
     * Verify packet signature using peer's signing public key and log the result
     */
    private fun verifyPacketSignatureWithLogging(packet: BitchatPacket, peerID: String) {
        try {
            // Check if packet has a signature
            if (packet.signature == null) {
                Log.d(TAG, "📝 Signature check for $peerID: NO_SIGNATURE (packet type ${packet.type})")
                return
            }
            
            // Try to get peer's signing public key from peer info
            val peerInfo = delegate?.getPeerInfo(peerID)
            val signingPublicKey = peerInfo?.signingPublicKey
            
            if (signingPublicKey == null) {
                Log.d(TAG, "📝 Signature check for $peerID: NO_SIGNING_KEY (packet type ${packet.type})")
                return
            }
            
            // Get the canonical packet data for signature verification (without signature)
            val packetDataForSigning = packet.toBinaryDataForSigning()
            if (packetDataForSigning == null) {
                Log.w(TAG, "📝 Signature check for $peerID: ENCODING_ERROR (packet type ${packet.type})")
                return
            }
            
            // Verify the signature using the peer's signing public key
            val signature = packet.signature!! // We already checked for null above
            val isSignatureValid = encryptionService.verifyEd25519Signature(
                signature,
                packetDataForSigning,
                signingPublicKey
            )
            
            if (isSignatureValid) {
                Log.d(TAG, "📝 Signature check for $peerID: ✅ VALID (packet type ${packet.type})")
            } else {
                Log.w(TAG, "📝 Signature check for $peerID: ❌ INVALID (packet type ${packet.type})")
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "📝 Signature check for $peerID: ERROR - ${e.message} (packet type ${packet.type})")
        }
    }
    
    /**
     * Check if we have encryption keys for a peer
     */
    fun hasKeysForPeer(peerID: String): Boolean {
        return encryptionService.hasEstablishedSession(peerID)
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
    fun sendHandshakeResponse(peerID: String, response: ByteArray)
    fun getPeerInfo(peerID: String): PeerInfo? // NEW: For signature verification
}
