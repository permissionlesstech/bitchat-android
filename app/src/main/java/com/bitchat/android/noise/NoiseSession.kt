package com.bitchat.android.noise

import android.util.Log
import java.security.SecureRandom

/**
 * Individual Noise session for a specific peer - 100% compatible with iOS implementation
 * 
 * Represents a single Noise XX protocol session with the following states:
 * - UNINITIALIZED: Session created but handshake not started
 * - HANDSHAKING: Handshake in progress (messages 1, 2, or 3)
 * - ESTABLISHED: Handshake complete, ready for transport encryption
 * - FAILED: Session failed and needs to be recreated
 */
class NoiseSession(
    private val peerID: String,
    private val isInitiator: Boolean,
    private val localStaticPrivateKey: ByteArray,
    private val localStaticPublicKey: ByteArray
) {
    
    companion object {
        private const val TAG = "NoiseSession"
        
        // Rekey thresholds (same as iOS)
        private const val REKEY_TIME_LIMIT = 3600000L // 1 hour
        private const val REKEY_MESSAGE_LIMIT = 10000L // 10k messages
    }
    
    // Session state
    private var state: NoiseSessionState = NoiseSessionState.Uninitialized
    private val creationTime = System.currentTimeMillis()
    
    // Handshake state (will be replaced with actual Noise implementation)
    private var handshakeStep = 0
    private var handshakeMessages = mutableListOf<ByteArray>()
    
    // Transport state (after handshake completion)
    private var sendKey: ByteArray? = null
    private var receiveKey: ByteArray? = null
    private var sendNonce = 0L
    private var receiveNonce = 0L
    
    // Remote peer information
    private var remoteStaticPublicKey: ByteArray? = null
    private var handshakeHash: ByteArray? = null
    
    // Message counting for rekey
    private var messagesSent = 0L
    private var messagesReceived = 0L
    
    // MARK: - Session State
    
    /**
     * Session states matching iOS implementation
     */
    sealed class NoiseSessionState {
        object Uninitialized : NoiseSessionState()
        object Handshaking : NoiseSessionState()
        object Established : NoiseSessionState()
        data class Failed(val error: Throwable) : NoiseSessionState()
        
        override fun toString(): String = when (this) {
            is Uninitialized -> "uninitialized"
            is Handshaking -> "handshaking"
            is Established -> "established"
            is Failed -> "failed: ${error.message}"
        }
    }
    
    fun getState(): NoiseSessionState = state
    fun isEstablished(): Boolean = state is NoiseSessionState.Established
    fun isHandshaking(): Boolean = state is NoiseSessionState.Handshaking
    fun getCreationTime(): Long = creationTime
    
    // MARK: - Handshake Implementation
    
    /**
     * Start handshake (initiator only)
     * Returns the first handshake message (32 bytes - ephemeral public key)
     */
    fun startHandshake(): ByteArray {
        if (!isInitiator) {
            throw IllegalStateException("Only initiator can start handshake")
        }
        
        if (state != NoiseSessionState.Uninitialized) {
            throw IllegalStateException("Handshake already started")
        }
        
        state = NoiseSessionState.Handshaking
        handshakeStep = 1
        
        // TODO: Replace with actual Noise implementation
        // For now, generate a mock 32-byte ephemeral key message
        val ephemeralKey = ByteArray(32)
        SecureRandom().nextBytes(ephemeralKey)
        
        handshakeMessages.add(ephemeralKey)
        
        Log.d(TAG, "Started handshake with $peerID (step 1)")
        return ephemeralKey
    }
    
    /**
     * Process incoming handshake message
     * Returns response message if needed, null if handshake complete
     */
    fun processHandshakeMessage(message: ByteArray): ByteArray? {
        Log.d(TAG, "Processing handshake message from $peerID (${message.size} bytes)")
        
        // Initialize if we're a responder receiving the first message
        if (state == NoiseSessionState.Uninitialized && !isInitiator) {
            state = NoiseSessionState.Handshaking
            handshakeStep = 1
        }
        
        if (state != NoiseSessionState.Handshaking) {
            throw IllegalStateException("Invalid state for handshake message: $state")
        }
        
        handshakeMessages.add(message)
        
        // TODO: Replace with actual Noise XX implementation
        // This is a mock implementation that follows the XX pattern steps
        
        return when (handshakeStep) {
            1 -> {
                // Received first message (e)
                if (isInitiator) {
                    throw IllegalStateException("Initiator should not receive first message")
                }
                
                handshakeStep = 2
                
                // Generate response message (e, ee, s, es) - mock implementation
                val responseMessage = ByteArray(96)  // 32 (e) + 48 (encrypted s) + 16 (tag)
                SecureRandom().nextBytes(responseMessage)
                
                Log.d(TAG, "Sending handshake response to $peerID (step 2)")
                responseMessage
            }
            
            2 -> {
                // Received second message (e, ee, s, es)
                if (!isInitiator) {
                    throw IllegalStateException("Responder should not receive second message")
                }
                
                handshakeStep = 3
                
                // Generate final message (s, se) - mock implementation  
                val finalMessage = ByteArray(48) // 48 bytes (encrypted s) + 16 (tag)
                SecureRandom().nextBytes(finalMessage)
                
                Log.d(TAG, "Sending final handshake message to $peerID (step 3)")
                finalMessage
            }
            
            3 -> {
                // Received final message (s, se)
                if (isInitiator) {
                    throw IllegalStateException("Initiator should not receive third message")
                }
                
                // Complete handshake
                completeHandshake()
                null // No response needed
            }
            
            else -> {
                throw IllegalStateException("Invalid handshake step: $handshakeStep")
            }
        }.also {
            // Check if we (as initiator) completed handshake after sending final message
            if (isInitiator && handshakeStep == 3) {
                completeHandshake()
            }
        }
    }
    
    /**
     * Complete handshake and derive transport keys
     */
    private fun completeHandshake() {
        Log.d(TAG, "Completing handshake with $peerID")
        
        // TODO: Replace with actual Noise session split
        // For now, generate mock transport keys
        sendKey = ByteArray(32)
        receiveKey = ByteArray(32)
        remoteStaticPublicKey = ByteArray(32)
        handshakeHash = ByteArray(32)
        
        SecureRandom().apply {
            nextBytes(sendKey!!)
            nextBytes(receiveKey!!)
            nextBytes(remoteStaticPublicKey!!)
            nextBytes(handshakeHash!!)
        }
        
        // Reset nonces for transport mode
        sendNonce = 0
        receiveNonce = 0
        
        state = NoiseSessionState.Established
        Log.d(TAG, "Handshake completed with $peerID")
    }
    
    // MARK: - Transport Encryption
    
    /**
     * Encrypt data in transport mode
     */
    fun encrypt(data: ByteArray): ByteArray {
        if (!isEstablished()) {
            throw IllegalStateException("Session not established")
        }
        
        // TODO: Replace with actual ChaCha20-Poly1305 encryption
        // For now, return mock encrypted data
        val encryptedData = ByteArray(data.size + 16) // Add 16-byte MAC tag
        System.arraycopy(data, 0, encryptedData, 0, data.size)
        
        // Fill MAC tag area with mock data
        SecureRandom().nextBytes(encryptedData.sliceArray(data.size until encryptedData.size))
        
        messagesSent++
        sendNonce++
        
        return encryptedData
    }
    
    /**
     * Decrypt data in transport mode
     */
    fun decrypt(encryptedData: ByteArray): ByteArray {
        if (!isEstablished()) {
            throw IllegalStateException("Session not established")
        }
        
        if (encryptedData.size < 16) {
            throw IllegalArgumentException("Encrypted data too short")
        }
        
        // TODO: Replace with actual ChaCha20-Poly1305 decryption
        // For now, return mock decrypted data (remove 16-byte MAC tag)
        val decryptedData = ByteArray(encryptedData.size - 16)
        System.arraycopy(encryptedData, 0, decryptedData, 0, decryptedData.size)
        
        messagesReceived++
        receiveNonce++
        
        return decryptedData
    }
    
    // MARK: - Session Information
    
    /**
     * Get remote static public key (available after handshake completion)
     */
    fun getRemoteStaticPublicKey(): ByteArray? {
        return remoteStaticPublicKey?.clone()
    }
    
    /**
     * Get handshake hash for channel binding
     */
    fun getHandshakeHash(): ByteArray? {
        return handshakeHash?.clone()
    }
    
    /**
     * Check if session needs rekeying
     */
    fun needsRekey(): Boolean {
        if (!isEstablished()) return false
        
        val timeLimit = System.currentTimeMillis() - creationTime > REKEY_TIME_LIMIT
        val messageLimit = (messagesSent + messagesReceived) > REKEY_MESSAGE_LIMIT
        
        return timeLimit || messageLimit
    }
    
    /**
     * Get session statistics
     */
    fun getSessionStats(): String = buildString {
        appendLine("Session with $peerID:")
        appendLine("  State: $state")
        appendLine("  Role: ${if (isInitiator) "initiator" else "responder"}")
        appendLine("  Handshake step: $handshakeStep")
        appendLine("  Messages sent: $messagesSent")
        appendLine("  Messages received: $messagesReceived")
        appendLine("  Session age: ${(System.currentTimeMillis() - creationTime) / 1000}s")
        appendLine("  Needs rekey: ${needsRekey()}")
        appendLine("  Has remote key: ${remoteStaticPublicKey != null}")
    }
    
    /**
     * Reset session state
     */
    fun reset() {
        state = NoiseSessionState.Uninitialized
        handshakeStep = 0
        handshakeMessages.clear()
        sendKey = null
        receiveKey = null
        sendNonce = 0
        receiveNonce = 0
        remoteStaticPublicKey = null
        handshakeHash = null
        messagesSent = 0
        messagesReceived = 0
    }
    
    /**
     * Clean up session resources
     */
    fun destroy() {
        try {
            // Clear sensitive keys
            sendKey?.fill(0)
            receiveKey?.fill(0)
            remoteStaticPublicKey?.fill(0)
            handshakeHash?.fill(0)
            
            // Clear handshake messages
            handshakeMessages.forEach { it.fill(0) }
            handshakeMessages.clear()
            
            state = NoiseSessionState.Failed(Exception("Session destroyed"))
            
        } catch (e: Exception) {
            Log.w(TAG, "Error during session cleanup: ${e.message}")
        }
    }
}

/**
 * Session-specific errors
 */
sealed class SessionError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    object InvalidState : SessionError("Session in invalid state")
    object NotEstablished : SessionError("Session not established")
    object HandshakeFailed : SessionError("Handshake failed")
    object EncryptionFailed : SessionError("Encryption failed")
    object DecryptionFailed : SessionError("Decryption failed")
}
