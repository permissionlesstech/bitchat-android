package com.bitchat.android.noise

import android.util.Log
import com.bitchat.android.noise.southernstorm.protocol.*
import com.bitchat.android.util.toHexString
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
        private const val NOISE_XX_PATTERN_LENGTH = 3
        
        // Noise Protocol Configuration (exactly matching iOS)
        private const val PROTOCOL_NAME = "Noise_XX_25519_ChaChaPoly_SHA256"
        
        // Rekey thresholds (same as iOS)
        private const val REKEY_TIME_LIMIT = 3600000L // 1 hour
        private const val REKEY_MESSAGE_LIMIT = 10000L // 10k messages
        
        // XX Pattern Message Sizes (exactly matching iOS implementation)
        private const val XX_MESSAGE_1_SIZE = 32      // -> e (ephemeral key only)
        private const val XX_MESSAGE_2_SIZE = 96      // <- e, ee, s, es (32 + 48) + 16 (MAC)
        private const val XX_MESSAGE_3_SIZE = 48      // -> s, se (encrypted static key)
        
        // Maximum payload size for safety
        private const val MAX_PAYLOAD_SIZE = 256
    }
    
    // Noise Protocol objects
    private var handshakeState: HandshakeState? = null
    private var sendCipher: CipherState? = null
    private var receiveCipher: CipherState? = null
    
    // Session state
    private var state: NoiseSessionState = NoiseSessionState.Uninitialized
    private val creationTime = System.currentTimeMillis()

    // Session counters
    private var currentPattern = 0;
    private var messagesSent = 0L
    private var messagesReceived = 0L
    
    // Thread safety for cipher operations
    // The noise-java CipherState objects are NOT thread-safe. Multiple concurrent
    // decrypt/encrypt operations can corrupt the internal nonce state.
    private val cipherLock = Any() // Dedicated lock for cipher operations
    
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
     * Initialize the Noise handshake - NOW USES PERSISTENT KEYS with our local fork
     * Our local fork properly supports setting pre-existing keys, enabling persistent identity
     */
    private fun initializeNoiseHandshake(role: Int) {
        try {
            Log.d(TAG, "Creating HandshakeState with role: ${if (role == HandshakeState.INITIATOR) "INITIATOR" else "RESPONDER"}")
            
            // LOGGING: Track Android handshake initialization (matching iOS) 
            Log.d(TAG, "=== ANDROID NOISE SESSION - BEFORE HANDSHAKE INIT ===")
            Log.d(TAG, "Creating NoiseHandshakeState for peer: $peerID")
            Log.d(TAG, "Role: ${if (role == HandshakeState.INITIATOR) "INITIATOR" else "RESPONDER"}")
            
            handshakeState = HandshakeState(PROTOCOL_NAME, role)
            Log.d(TAG, "HandshakeState created successfully")
            
            Log.d(TAG, "=== ANDROID NOISE SESSION - AFTER HANDSHAKE INIT ===")
            Log.d(TAG, "NoiseHandshakeState created and mixPreMessageKeys() completed")
            
            if (handshakeState?.needsLocalKeyPair() == true) {
                Log.d(TAG, "Local static key pair is required for XX pattern")
                
                val localKeyPair = handshakeState?.getLocalKeyPair()
                if (localKeyPair != null) {
                    // FIXED: Use the provided persistent identity keys with our local fork
                    // Our local fork properly supports setting pre-existing keys
                    Log.d(TAG, "Setting persistent static identity keys...")
                    
                    localKeyPair.setPrivateKey(localStaticPrivateKey, 0)
                    
                    if (!localKeyPair.hasPrivateKey() || !localKeyPair.hasPublicKey()) {
                        throw IllegalStateException("Failed to set static identity keys - local fork issue")
                    }
                    
                    Log.d(TAG, "✓ Successfully set persistent static identity keys")
                    Log.d(TAG, "Algorithm: ${localKeyPair.dhName}")
                    Log.d(TAG, "Private key length: ${localKeyPair.privateKeyLength}")
                    Log.d(TAG, "Public key length: ${localKeyPair.publicKeyLength}")
                    
                    // Verify the keys were set correctly
                    val verifyPrivate = ByteArray(32)
                    val verifyPublic = ByteArray(32)
                    localKeyPair.getPrivateKey(verifyPrivate, 0)
                    localKeyPair.getPublicKey(verifyPublic, 0)
                    
                    Log.d(TAG, "Persistent identity public key: ${localStaticPublicKey.joinToString("") { "%02x".format(it) }}")
                    Log.d(TAG, "Set public key:               ${verifyPublic.joinToString("") { "%02x".format(it) }}")

                } else {
                    throw IllegalStateException("HandshakeState returned null for local key pair")
                }
                
            } else {
                Log.d(TAG, "Local static key pair not needed for this handshake pattern/role")
            }
            handshakeState?.start()
            Log.d(TAG, "Handshake state started successfully with persistent identity keys")

        } catch (e: Exception) {
            Log.e(TAG, "Exception during handshake initialization: ${e.message}", e)
            throw e
        }
    }
    


    // MARK: - Real Handshake Implementation
    
    /**
     * Start handshake as INITIATOR
     * Returns e, the first handshake message for XX pattern (32 bytes)
     */
    @Synchronized
    fun startHandshake(): ByteArray {
        Log.d(TAG, "Starting noise XX handshake with $peerID as INITIATOR")

        if (!isInitiator) {
            throw IllegalStateException("Only initiator can start handshake")
        }
        
        if (state != NoiseSessionState.Uninitialized) {
            throw IllegalStateException("Handshake already started")
        }

        try {
            // Initialize handshake as initiator 
            initializeNoiseHandshake(HandshakeState.INITIATOR)
            state = NoiseSessionState.Handshaking
            
            val messageBuffer = ByteArray(XX_MESSAGE_1_SIZE)
            val handshakeStateLocal = handshakeState ?: throw IllegalStateException("Handshake state is null")
            val messageLength = handshakeStateLocal.writeMessage(messageBuffer, 0, null, 0, 0)
            currentPattern++
            val firstMessage = messageBuffer.copyOf(messageLength)
            
            // Validate message size matches XX pattern expectations
            if (firstMessage.size != XX_MESSAGE_1_SIZE) {
                Log.w(TAG, "Warning: XX message 1 size ${firstMessage.size} != expected $XX_MESSAGE_1_SIZE")
            }
            
            Log.d(TAG, "Sending XX handshake message 1 to $peerID (${firstMessage.size} bytes) currentPattern: $currentPattern")
            return firstMessage
        } catch (e: Exception) {
            state = NoiseSessionState.Failed(e)
            Log.e(TAG, "Failed to start handshake: ${e.message}")
            throw e
        }
    }
    
    /**
     * Process incoming handshake as RESPONDER
     * Returns e, ee
     */
    @Synchronized
    fun processHandshakeMessage(message: ByteArray): ByteArray? {
        Log.d(TAG, "Processing handshake message from $peerID (${message.size} bytes)")
        
        try {
            // Initialize as responder if receiving first message
            if (state == NoiseSessionState.Uninitialized && !isInitiator) {
                initializeNoiseHandshake(HandshakeState.RESPONDER)
                state = NoiseSessionState.Handshaking
                Log.d(TAG, "Initialized as RESPONDER for XX handshake with $peerID")
            }
            
            if (state != NoiseSessionState.Handshaking) {
                throw IllegalStateException("Invalid state for handshake: $state")
            }
            
            val handshakeStateLocal = handshakeState ?: throw IllegalStateException("Handshake state is null")
            
            // Let the Noise library validate message sizes and handle the flow
            val payloadBuffer = ByteArray(XX_MESSAGE_2_SIZE + MAX_PAYLOAD_SIZE)  // Buffer for any payload data
            
            // Read the incoming message - the Noise library will handle validation
            val payloadLength = handshakeStateLocal.readMessage(message, 0, message.size, payloadBuffer, 0)
            currentPattern++
            Log.d(TAG, "Read handshake message, payload length: $payloadLength currentPattern: $currentPattern")
            
            // Check what action the handshake state wants us to take next
            val action = handshakeStateLocal.getAction()
            Log.d(TAG, "Handshake action after processing message: $action")
            
            return when (action) {
                HandshakeState.WRITE_MESSAGE -> {
                    // Noise library says we need to send a response
                    val responseBuffer = ByteArray(XX_MESSAGE_2_SIZE + MAX_PAYLOAD_SIZE) // Large buffer for any response
                    val responseLength = handshakeStateLocal.writeMessage(responseBuffer, 0, null, 0, 0)
                    currentPattern++
                    val response = responseBuffer.copyOf(responseLength)
                    
                    Log.d(TAG, "Generated handshake response: ${response.size} bytes, action still: ${handshakeStateLocal.getAction()} currentPattern: $currentPattern")
                    completeHandshake()
                    response
                }
                
                HandshakeState.SPLIT -> {
                    // Handshake complete, split into transport keys
                    completeHandshake()
                    Log.d(TAG, "SPLIT ✅ XX handshake completed with $peerID")
                    null
                }
                
                HandshakeState.FAILED -> {
                    throw Exception("Handshake failed - Noise library reported FAILED state")
                }
                
                HandshakeState.READ_MESSAGE -> {
                    // Noise library expects us to read another message
                    Log.d(TAG, "Handshake waiting for next message from $peerID")
                    null
                }
                
                else -> {
                    Log.d(TAG, "Handshake action: $action - no immediate action needed")
                    null
                }
            }
            
        } catch (e: Exception) {
            state = NoiseSessionState.Failed(e)
            Log.e(TAG, "Handshake failed with $peerID: ${e.message}", e)
            throw e
        }
    }
    
    /**
     * Complete handshake and derive transport keys
     */
    @Synchronized
    private fun completeHandshake() {
        if (currentPattern < NOISE_XX_PATTERN_LENGTH) {
            return
        }

        Log.d(TAG, "Completing XX handshake with $peerID")
        
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
                    Log.d(TAG, "Remote static public key: ${remoteStaticPublicKey!!.joinToString("") { "%02x".format(it) }}")
                }
            }
            
            // Extract handshake hash for channel binding
            handshakeHash = handshakeState?.getHandshakeHash()
            
            // Clean up handshake state
            handshakeState?.destroy()
            handshakeState = null
            
            messagesSent = 0
            messagesReceived = 0
            currentPattern = 0
            
            state = NoiseSessionState.Established
            Log.d(TAG, "Handshake completed with $peerID as isInitiator: $isInitiator - transport keys derived")
            Log.d(TAG, "✅ XX handshake completed with $peerID")
        } catch (e: Exception) {
            state = NoiseSessionState.Failed(e)
            Log.e(TAG, "Failed to complete handshake: ${e.message}")
            throw e
        }
    }

    // MARK: - Transport Encryption
    
    /**
     * Encrypt data in transport mode using real ChaCha20-Poly1305
     */
    fun encrypt(data: ByteArray): ByteArray {
        // Pre-check state without holding cipher lock
        if (!isEstablished()) {
            throw IllegalStateException("Session not established")
        }
        
        // Critical section: Use dedicated cipher lock to protect CipherState nonce corruption
        synchronized(cipherLock) {
            // Double-check state inside lock
            if (!isEstablished()) {
                throw IllegalStateException("Session not established during cipher operation")
            }
            
            if (sendCipher == null) {
                throw IllegalStateException("Send cipher not available")
            }
            
            try {
                // assert that sendCipher!!.macLengt is 16:
                if (sendCipher!!.macLength != 16) {
                    throw IllegalStateException("Send cipher MAC length is not 16")
                }
                
                val ciphertext = ByteArray(data.size + sendCipher!!.macLength) // Add space for MAC tag
                val ciphertextLength = sendCipher!!.encryptWithAd(null, data, 0, ciphertext, 0, data.size)
                messagesSent++
                
                val result = ciphertext.copyOf(ciphertextLength)
                Log.d(TAG, "✅ ANDROID ENCRYPT: ${data.size} → ${result.size} bytes for $peerID (msg #$messagesSent, role: ${if (isInitiator) "INITIATOR" else "RESPONDER"})")
                return result
                
            } catch (e: Exception) {
                Log.e(TAG, "Real encryption failed - exception: ${e.message}")
                
                // ENHANCED: Log cipher state for debugging
                if (sendCipher != null) {
                    Log.e(TAG, "Send cipher state: ${sendCipher!!.javaClass.simpleName}")
                }
                
                throw SessionError.EncryptionFailed
            }
        }
    }
    
    /**
     * Decrypt data in transport mode using real ChaCha20-Poly1305
     */
    fun decrypt(encryptedData: ByteArray): ByteArray {
        // Pre-check state without holding cipher lock
        if (!isEstablished()) {
            throw IllegalStateException("Session not established")
        }
        
        // Critical section: Use dedicated cipher lock to protect CipherState nonce corruption
        synchronized(cipherLock) {
            // Double-check state inside lock
            if (!isEstablished()) {
                throw IllegalStateException("Session not established during cipher operation")
            }
            
            if (receiveCipher == null) {
                throw IllegalStateException("Receive cipher not available")
            }

            try {
                val plaintext = ByteArray(encryptedData.size) // Over-allocate for safety
                val plaintextLength = receiveCipher!!.decryptWithAd(null, encryptedData, 0, plaintext, 0, encryptedData.size)
                messagesReceived++

                val result = plaintext.copyOf(plaintextLength)
                Log.d(TAG, "✅ ANDROID DECRYPT: ${encryptedData.size} → ${result.size} bytes from $peerID (msg #$messagesReceived, role: ${if (isInitiator) "INITIATOR" else "RESPONDER"})")
                return result
            } catch (e: Exception) {
                Log.e(TAG, "Decryption failed - exception: ${e.message}")
                
                // ENHANCED: Log cipher state and session details for debugging
                if (receiveCipher != null) {
                    Log.e(TAG, "Receive cipher state: ${receiveCipher!!.javaClass.simpleName}")
                }
                Log.e(TAG, "Session state: $state, messages received: $messagesReceived")
                Log.e(TAG, "Input data size: ${encryptedData.size} bytes")
                
                throw SessionError.DecryptionFailed
            }
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
