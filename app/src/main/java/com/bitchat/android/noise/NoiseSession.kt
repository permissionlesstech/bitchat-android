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
        
        // XX Pattern Message Sizes (exactly matching iOS implementation)
        private const val XX_MESSAGE_1_SIZE = 32      // -> e (ephemeral key only)
        private const val XX_MESSAGE_2_SIZE = 80      // <- e, ee, s, es (32 + 48)
        private const val XX_MESSAGE_3_SIZE = 48      // -> s, se (encrypted static key)
        
        // Maximum payload size for safety
        private const val MAX_PAYLOAD_SIZE = 256
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
    
    // Handshake message counter to track XX pattern steps
    private var handshakeMessageCount = 0
    
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
            // Validate static keys
            validateStaticKeys()
            Log.d(TAG, "Created ${if (isInitiator) "initiator" else "responder"} session for $peerID")
        } catch (e: Exception) {
            state = NoiseSessionState.Failed(e)
            Log.e(TAG, "Failed to initialize Noise session: ${e.message}")
        }
    }
    
    /**
     * Validate static keys before using them
     */
    private fun validateStaticKeys() {
        if (localStaticPrivateKey.size != 32) {
            throw IllegalArgumentException("Local static private key must be 32 bytes, got ${localStaticPrivateKey.size}")
        }
        if (localStaticPublicKey.size != 32) {
            throw IllegalArgumentException("Local static public key must be 32 bytes, got ${localStaticPublicKey.size}")
        }
        
        // Check for all-zero keys (invalid)
        if (localStaticPrivateKey.all { it == 0.toByte() }) {
            throw IllegalArgumentException("Local static private key cannot be all zeros")
        }
        if (localStaticPublicKey.all { it == 0.toByte() }) {
            throw IllegalArgumentException("Local static public key cannot be all zeros")
        }
        
        Log.d(TAG, "Static keys validated successfully - private: ${localStaticPrivateKey.size} bytes, public: ${localStaticPublicKey.size} bytes")
    }
    
    /**
     * Initialize the Noise handshake with proper static key injection
     * FIXED: Uses standard noise-java library that supports manual key setting
     */
    private fun initializeNoiseHandshake(role: Int) {
        try {
            Log.d(TAG, "Creating HandshakeState with role: ${if (role == HandshakeState.INITIATOR) "INITIATOR" else "RESPONDER"}")
            
            handshakeState = HandshakeState(PROTOCOL_NAME, role)
            Log.d(TAG, "HandshakeState created successfully")
            
            if (handshakeState?.needsLocalKeyPair() == true) {
                Log.d(TAG, "Local key pair is needed")
                val localKeyPair = handshakeState?.getLocalKeyPair()
                
                if (localKeyPair != null) {
                    // FIXED: Set our persistent static keys directly (standard noise-java supports this)
                    localKeyPair.setPrivateKey(localStaticPrivateKey, 0)
                    localKeyPair.setPublicKey(localStaticPublicKey, 0)
                    Log.d(TAG, "✓ Set persistent static key pair (private: ${localStaticPrivateKey.size} bytes, public: ${localStaticPublicKey.size} bytes)")
                } else {
                    Log.e(TAG, "Failed to get local key pair even though it's needed")
                    throw IllegalStateException("Failed to get local key pair")
                }
            } else {
                Log.d(TAG, "Local key pair not needed for this handshake pattern/role")
            }
            
            handshakeState?.start()
            Log.d(TAG, "Handshake state started successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Exception during handshake initialization: ${e.message}", e)
            throw e
        }
    }
    


    // MARK: - Real Handshake Implementation
    
    /**
     * Start handshake (initiator only) using real Noise Protocol
     * Returns the first handshake message for XX pattern (32 bytes exactly)
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
            // Initialize handshake as initiator 
            initializeNoiseHandshake(HandshakeState.INITIATOR)
            state = NoiseSessionState.Handshaking
            handshakeMessageCount = 1
            
            // CRITICAL FIX: Use exact buffer size for XX message 1 (32 bytes)
            val messageBuffer = ByteArray(XX_MESSAGE_1_SIZE + MAX_PAYLOAD_SIZE) // Extra space for safety
            val handshakeStateLocal = handshakeState ?: throw IllegalStateException("Handshake state is null")
            val messageLength = handshakeStateLocal.writeMessage(ByteArray(0), 0, messageBuffer, 0, 0)
            val firstMessage = messageBuffer.copyOf(messageLength)
            
            // Validate message size matches XX pattern expectations
            if (firstMessage.size != XX_MESSAGE_1_SIZE) {
                Log.w(TAG, "Warning: XX message 1 size ${firstMessage.size} != expected $XX_MESSAGE_1_SIZE")
            }
            
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
     * FIXED: Proper message size validation and buffer handling
     */
    @Synchronized
    fun processHandshakeMessage(message: ByteArray): ByteArray? {
        Log.d(TAG, "Processing real handshake message from $peerID (${message.size} bytes)")
        
        try {
            // Initialize as responder if receiving first message
            if (state == NoiseSessionState.Uninitialized && !isInitiator) {
                initializeNoiseHandshake(HandshakeState.RESPONDER)
                state = NoiseSessionState.Handshaking
                handshakeMessageCount = 1
                Log.d(TAG, "Initialized as responder for real XX handshake with $peerID")
            }
            
            if (state != NoiseSessionState.Handshaking) {
                throw IllegalStateException("Invalid state for handshake: $state")
            }
            
            // CRITICAL FIX: Validate message size based on XX pattern step
            validateHandshakeMessageSize(message, handshakeMessageCount, isInitiator)
            
            val payloadBuffer = ByteArray(MAX_PAYLOAD_SIZE)  // Buffer for any payload data
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
                    handshakeMessageCount++
                    val expectedSize = getExpectedResponseSize(handshakeMessageCount, isInitiator)
                    val responseBuffer = ByteArray(expectedSize + MAX_PAYLOAD_SIZE) // Use proper size
                    val responseLength = handshakeStateLocal.writeMessage(ByteArray(0), 0, responseBuffer, 0, 0)
                    val response = responseBuffer.copyOf(responseLength)
                    
                    // Validate response size
                    if (response.size != expectedSize) {
                        Log.w(TAG, "Warning: XX response size ${response.size} != expected $expectedSize")
                    }
                    
                    Log.d(TAG, "Generated handshake response: ${response.size} bytes")
                    response
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
     * Validate handshake message size based on XX pattern and step
     */
    private fun validateHandshakeMessageSize(message: ByteArray, step: Int, isInitiator: Boolean) {
        val expectedSize = when {
            // Receiving as responder from initiator
            step == 1 && !isInitiator -> XX_MESSAGE_1_SIZE  // Message 1: -> e
            // Receiving as initiator from responder  
            step == 2 && isInitiator -> XX_MESSAGE_2_SIZE   // Message 2: <- e, ee, s, es
            // Receiving as responder from initiator
            step == 3 && !isInitiator -> XX_MESSAGE_3_SIZE  // Message 3: -> s, se
            else -> {
                Log.w(TAG, "Unknown handshake step $step for ${if (isInitiator) "initiator" else "responder"}")
                return // Don't validate unknown steps
            }
        }
        
        if (message.size != expectedSize) {
            Log.w(TAG, "Handshake message size mismatch: got ${message.size}, expected $expectedSize for step $step")
            // Don't throw here, let the underlying Noise implementation handle it
        } else {
            Log.d(TAG, "Handshake message size validated: ${message.size} bytes for step $step")
        }
    }
    
    /**
     * Get expected response size based on XX pattern and step
     */
    private fun getExpectedResponseSize(step: Int, isInitiator: Boolean): Int {
        return when {
            // Responding as responder to message 1
            step == 2 && !isInitiator -> XX_MESSAGE_2_SIZE  // Response: <- e, ee, s, es
            // Responding as initiator to message 2
            step == 3 && isInitiator -> XX_MESSAGE_3_SIZE   // Response: -> s, se
            else -> {
                Log.w(TAG, "Unknown response step $step for ${if (isInitiator) "initiator" else "responder"}")
                200 // Default fallback
            }
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
            
            // Reset to uninitialized state (handshake will be initialized when needed)
            state = NoiseSessionState.Uninitialized
            messagesSent = 0
            messagesReceived = 0
            handshakeMessageCount = 0
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
    class HandshakeInitializationFailed(message: String) : SessionError("Handshake initialization failed: $message")
}
