package com.bitchat.android.noise

import android.util.Log
import com.southernstorm.noise.protocol.*
import java.security.SecureRandom

/**
 * Individual Noise session for a specific peer - REAL IMPLEMENTATION with noise-java
 * 100% compatible with iOS bitchat Noise Protocol
 */
class NoiseSession(
    private val peerID: String,
    private val isInitiator: Boolean,
    private val localStaticPrivateKey: ByteArray,
    private val localStaticPublicKey: ByteArray
) {
    
    companion object {
        private const val TAG = "NoiseSession"
        
        // Noise Protocol Configuration (exactly matching iOS)
        private const val PROTOCOL_NAME = "Noise_XX_25519_ChaChaPoly_SHA256"
        
        // Rekey thresholds (same as iOS)
        private const val REKEY_TIME_LIMIT = 3600000L // 1 hour
        private const val REKEY_MESSAGE_LIMIT = 10000L // 10k messages
    }
    
    // Real Noise Protocol objects
    private var handshakeState: HandshakeState? = null
    private var sendCipher: CipherState? = null
    private var receiveCipher: CipherState? = null
    
    // Session state
    private var state: NoiseSessionState = NoiseSessionState.Uninitialized
    private val creationTime = System.currentTimeMillis()
    
    // Session counters
    private var messagesSent = 0L
    private var messagesReceived = 0L
    
    // Remote peer information  
    private var remoteStaticPublicKey: ByteArray? = null
    private var handshakeHash: ByteArray? = null
    
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
    
    init {
        try {
            initializeNoiseHandshake()
            Log.d(TAG, "Created ${if (isInitiator) "initiator" else "responder"} session for $peerID")
        } catch (e: Exception) {
            state = NoiseSessionState.Failed(e)
            Log.e(TAG, "Failed to initialize Noise session: ${e.message}")
        }
    }
    
    /**
     * Initialize the real Noise handshake state using noise-java
     */
    private fun initializeNoiseHandshake() {
        val role = if (isInitiator) HandshakeState.INITIATOR else HandshakeState.RESPONDER
        handshakeState = HandshakeState(PROTOCOL_NAME, role)
        
        // Set up local static key pair properly
        if (handshakeState?.needsLocalKeyPair() == true) {
            val localKeyPair = handshakeState?.getLocalKeyPair()
            if (localKeyPair != null) {
                // Set the static private key we loaded/generated  
                localKeyPair.setPrivateKey(localStaticPrivateKey, 0)
                Log.d(TAG, "Set local static key for handshake state")
            }
        }
        
        // Start the handshake
        handshakeState?.start()
        
        Log.d(TAG, "Initialized real Noise handshake state for $peerID")
    }
    
    // MARK: - Real Handshake Implementation
    
    /**
     * Start handshake (initiator only) using real Noise Protocol
     * Returns the first handshake message for XX pattern
     */
    @Synchronized
    fun startHandshake(): ByteArray {
        if (!isInitiator) {
            throw IllegalStateException("Only initiator can start handshake")
        }
        
        if (state != NoiseSessionState.Uninitialized) {
            throw IllegalStateException("Handshake already started")
        }
        
        Log.d(TAG, "Starting real XX handshake with $peerID as initiator")
        
        try {
            state = NoiseSessionState.Handshaking
            
            val messageBuffer = ByteArray(512) // Increased buffer size for XX pattern
            val handshakeStateLocal = handshakeState ?: throw IllegalStateException("Handshake state is null")
            val messageLength = handshakeStateLocal.writeMessage(ByteArray(0), 0, messageBuffer, 0, 0)
            val firstMessage = messageBuffer.copyOf(messageLength)
            
            Log.d(TAG, "Sent real XX handshake message 1 to $peerID (${firstMessage.size} bytes)")
            return firstMessage
        } catch (e: Exception) {
            state = NoiseSessionState.Failed(e)
            Log.e(TAG, "Failed to start handshake: ${e.message}")
            throw e
        }
    }
    
    /**
     * Process incoming handshake message using real Noise Protocol
     * Returns response message if needed, null if handshake complete
     */
    @Synchronized
    fun processHandshakeMessage(message: ByteArray): ByteArray? {
        Log.d(TAG, "Processing real handshake message from $peerID (${message.size} bytes)")
        
        try {
            // Initialize as responder if receiving first message
            if (state == NoiseSessionState.Uninitialized && !isInitiator) {
                state = NoiseSessionState.Handshaking
                Log.d(TAG, "Initialized as responder for real XX handshake with $peerID")
            }
            
            if (state != NoiseSessionState.Handshaking) {
                throw IllegalStateException("Invalid state for handshake: $state")
            }
            
            val payloadBuffer = ByteArray(256)  // Buffer for any payload data
            val handshakeStateLocal = handshakeState ?: throw IllegalStateException("Handshake state is null")
            
            // Read the incoming message
            val payloadLength = handshakeStateLocal.readMessage(message, 0, message.size, payloadBuffer, 0)
            Log.d(TAG, "Read handshake message, payload length: $payloadLength")
            
            // Check the handshake action state
            val action = handshakeStateLocal.getAction()
            Log.d(TAG, "Handshake action after read: $action")
            
            return when (action) {
                HandshakeState.WRITE_MESSAGE -> {
                    // Need to send a response
                    val responseBuffer = ByteArray(512) // Increased buffer size for XX pattern message 2
                    val responseLength = handshakeStateLocal.writeMessage(ByteArray(0), 0, responseBuffer, 0, 0)
                    responseBuffer.copyOf(responseLength).also {
                        Log.d(TAG, "Generated handshake response: ${it.size} bytes")
                    }
                }
                
                HandshakeState.SPLIT -> {
                    // Handshake complete, split into transport keys
                    completeHandshake()
                    Log.d(TAG, "Real XX handshake completed with $peerID")
                    null
                }
                
                HandshakeState.FAILED -> {
                    throw Exception("Handshake failed - action state is FAILED")
                }
                
                else -> {
                    Log.d(TAG, "Handshake action: $action - no response needed")
                    null
                }
            }
            
        } catch (e: Exception) {
            state = NoiseSessionState.Failed(e)
            Log.e(TAG, "Real handshake failed with $peerID: ${e.message}", e)
            throw e
        }
    }
    
    /**
     * Complete handshake and derive real transport keys
     */
    private fun completeHandshake() {
        Log.d(TAG, "Completing real XX handshake with $peerID")
        
        try {
            // Split handshake state into transport ciphers
            val cipherPair = handshakeState?.split()
            sendCipher = cipherPair?.getSender()
            receiveCipher = cipherPair?.getReceiver()
            
            // Extract remote static key if available
            if (handshakeState?.hasRemotePublicKey() == true) {
                val remoteDH = handshakeState?.getRemotePublicKey()
                if (remoteDH != null) {
                    remoteStaticPublicKey = ByteArray(32)
                    remoteDH.getPublicKey(remoteStaticPublicKey!!, 0)
                }
            }
            
            // Extract handshake hash for channel binding
            handshakeHash = handshakeState?.getHandshakeHash()
            
            // Clean up handshake state
            handshakeState?.destroy()
            handshakeState = null
            
            messagesSent = 0
            messagesReceived = 0
            
            state = NoiseSessionState.Established
            Log.d(TAG, "Real XX handshake completed with $peerID - transport keys derived")
        } catch (e: Exception) {
            state = NoiseSessionState.Failed(e)
            Log.e(TAG, "Failed to complete handshake: ${e.message}")
            throw e
        }
    }
    
    // MARK: - Real Transport Encryption
    
    /**
     * Encrypt data in transport mode using real ChaCha20-Poly1305
     */
    @Synchronized
    fun encrypt(data: ByteArray): ByteArray {
        if (!isEstablished()) {
            throw IllegalStateException("Session not established")
        }
        
        if (sendCipher == null) {
            throw IllegalStateException("Send cipher not available")
        }
        
        try {
            val ciphertext = ByteArray(data.size + 16) // Add space for MAC tag
            val ciphertextLength = sendCipher!!.encryptWithAd(null, data, 0, ciphertext, 0, data.size)
            
            messagesSent++
            
            val result = ciphertext.copyOf(ciphertextLength)
            Log.d(TAG, "Real encrypted ${data.size} bytes to ${result.size} bytes for $peerID")
            return result
            
        } catch (e: Exception) {
            Log.e(TAG, "Real encryption failed: ${e.message}")
            throw SessionError.EncryptionFailed
        }
    }
    
    /**
     * Decrypt data in transport mode using real ChaCha20-Poly1305
     */
    @Synchronized
    fun decrypt(encryptedData: ByteArray): ByteArray {
        if (!isEstablished()) {
            throw IllegalStateException("Session not established")
        }
        
        if (receiveCipher == null) {
            throw IllegalStateException("Receive cipher not available")
        }
        
        try {
            val plaintext = ByteArray(encryptedData.size) // Over-allocate for safety
            val plaintextLength = receiveCipher!!.decryptWithAd(null, encryptedData, 0, plaintext, 0, encryptedData.size)
            
            messagesReceived++
            
            val result = plaintext.copyOf(plaintextLength)
            Log.d(TAG, "Real decrypted ${encryptedData.size} bytes to ${result.size} bytes from $peerID")
            return result
            
        } catch (e: Exception) {
            Log.e(TAG, "Real decryption failed: ${e.message}")
            throw SessionError.DecryptionFailed
        }
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
        appendLine("NoiseSession with $peerID:")
        appendLine("  State: $state")
        appendLine("  Role: ${if (isInitiator) "initiator" else "responder"}")
        appendLine("  Messages sent: $messagesSent")
        appendLine("  Messages received: $messagesReceived")
        appendLine("  Session age: ${(System.currentTimeMillis() - creationTime) / 1000}s")
        appendLine("  Needs rekey: ${needsRekey()}")
        appendLine("  Has remote key: ${remoteStaticPublicKey != null}")
        appendLine("  Has send cipher: ${sendCipher != null}")
        appendLine("  Has receive cipher: ${receiveCipher != null}")
    }
    
    /**
     * Reset session state
     */
    @Synchronized
    fun reset() {
        try {
            // Destroy existing state
            destroy()
            
            // Reinitialize
            initializeNoiseHandshake()
            state = NoiseSessionState.Uninitialized
            messagesSent = 0
            messagesReceived = 0
            remoteStaticPublicKey = null
            handshakeHash = null
        } catch (e: Exception) {
            state = NoiseSessionState.Failed(e)
            Log.e(TAG, "Failed to reset session: ${e.message}")
        }
    }
    
    /**
     * Clean up session resources securely
     */
    @Synchronized
    fun destroy() {
        try {
            // Destroy Noise objects
            sendCipher?.destroy()
            receiveCipher?.destroy()
            handshakeState?.destroy()
            
            // Clear sensitive data
            remoteStaticPublicKey?.fill(0)
            handshakeHash?.fill(0)
            
            // Null out references
            sendCipher = null
            receiveCipher = null
            handshakeState = null
            remoteStaticPublicKey = null
            handshakeHash = null
            
            if (state !is NoiseSessionState.Failed) {
                state = NoiseSessionState.Failed(Exception("Session destroyed"))
            }
            
            Log.d(TAG, "Session destroyed for $peerID")
            
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
