package com.bitchat.android.noise

import android.content.Context
import android.util.Log
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.mesh.PeerFingerprintManager
import com.bitchat.android.noise.southernstorm.protocol.Noise
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Main Noise encryption service - 100% compatible with iOS implementation
 * 
 * This service manages:
 * - Static identity keys (persistent across sessions)
 * - Noise session management for each peer
 * - Channel encryption using password-derived keys
 * - Peer fingerprint mapping and identity persistence
 */
class NoiseEncryptionService(private val context: Context) {
    
    companion object {
        private const val TAG = "NoiseEncryptionService"
        
        // Session limits for performance and security
        private const val REKEY_TIME_LIMIT = com.bitchat.android.util.AppConstants.Noise.REKEY_TIME_LIMIT_MS // 1 hour (same as iOS)
        private const val REKEY_MESSAGE_LIMIT = com.bitchat.android.util.AppConstants.Noise.REKEY_MESSAGE_LIMIT_ENCRYPTION // 1k messages (matches iOS) (same as iOS)
    }
    
    // Static identity key (persistent across app restarts) - loaded from secure storage
    private val staticIdentityPrivateKey: ByteArray
    private val staticIdentityPublicKey: ByteArray
    
    // Ed25519 signing key (persistent across app restarts) - loaded from secure storage
    private val signingPrivateKey: ByteArray
    private val signingPublicKey: ByteArray
    
    // Session management
    private val sessionManager: NoiseSessionManager
    
    // Channel encryption for password-protected channels
    private val channelEncryption = NoiseChannelEncryption()
    
    // Identity management for peer ID rotation support
    private val identityStateManager: SecureIdentityStateManager
    
    // Centralized fingerprint management - NO LOCAL STORAGE
    private val fingerprintManager = PeerFingerprintManager.getInstance()
    
    // Callbacks
    var onPeerAuthenticated: ((String, String) -> Unit)? = null // (peerID, fingerprint)
    var onHandshakeRequired: ((String) -> Unit)? = null // peerID needs handshake
    
    init {
        // Initialize identity state manager for persistent storage
        identityStateManager = SecureIdentityStateManager(context)
        
        // Load or create static identity key (persistent across sessions)
        val loadedKeyPair = identityStateManager.loadStaticKey()
        if (loadedKeyPair != null) {
            staticIdentityPrivateKey = loadedKeyPair.first
            staticIdentityPublicKey = loadedKeyPair.second
            Log.d(TAG, "Loaded existing static identity key: ${calculateFingerprint(staticIdentityPublicKey)}")
        } else {
            // Generate new identity key pair
            val keyPair = generateKeyPair()
            staticIdentityPrivateKey = keyPair.first
            staticIdentityPublicKey = keyPair.second
            
            // Save to secure storage
            identityStateManager.saveStaticKey(staticIdentityPrivateKey, staticIdentityPublicKey)
            Log.d(TAG, "Generated and saved new static identity key")
        }
        
        // Load or create Ed25519 signing key (persistent across sessions)
        val loadedSigningKeyPair = identityStateManager.loadSigningKey()
        if (loadedSigningKeyPair != null) {
            signingPrivateKey = loadedSigningKeyPair.first
            signingPublicKey = loadedSigningKeyPair.second
            Log.d(TAG, "Loaded existing Ed25519 signing key")
        } else {
            // Generate new Ed25519 signing key pair
            val signingKeyPair = generateEd25519KeyPair()
            signingPrivateKey = signingKeyPair.first
            signingPublicKey = signingKeyPair.second
            
            // Save to secure storage
            identityStateManager.saveSigningKey(signingPrivateKey, signingPublicKey)
            Log.d(TAG, "Generated and saved new Ed25519 signing key")
        }
        
        // Initialize session manager
        sessionManager = NoiseSessionManager(staticIdentityPrivateKey, staticIdentityPublicKey)
        
        // Set up session callbacks
        sessionManager.onSessionEstablished = { peerID, remoteStaticKey ->
            handleSessionEstablished(peerID, remoteStaticKey)
        }
    }
    
    // MARK: - Public Interface
    
    /**
     * Get our static public key data for sharing (32 bytes)
     */
    fun getStaticPublicKeyData(): ByteArray {
        return staticIdentityPublicKey.clone()
    }

    /**
     * Get our signing public key data for sharing (32 bytes)
     */
    fun getSigningPublicKeyData(): ByteArray {
        return signingPublicKey.clone()
    }
    
    /**
     * Get our identity fingerprint (SHA-256 hash of static public key)
     */
    fun getIdentityFingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(staticIdentityPublicKey)
        return hash.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Get peer's public key data (if we have a session)
     */
    fun getPeerPublicKeyData(peerID: String): ByteArray? {
        return sessionManager.getRemoteStaticKey(peerID)
    }
    
    /**
     * Clear persistent identity (for panic mode)
     */
    fun clearPersistentIdentity() {
        identityStateManager.clearIdentityData()
    }
    
    // MARK: - Handshake Management
    
    /**
     * Initiate a Noise handshake with a peer
     * Returns the first handshake message to send
     */
    fun initiateHandshake(peerID: String): ByteArray? {
        return try {
            sessionManager.initiateHandshake(peerID)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate handshake with $peerID: ${e.message}")
            null
        }
    }
    
    /**
     * Process an incoming handshake message
     * Returns response message if needed, null if handshake complete or failed
     */
    fun processHandshakeMessage(data: ByteArray, peerID: String): ByteArray? {
        return try {
            sessionManager.processHandshakeMessage(peerID, data)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process handshake from $peerID: ${e.message}")
            null
        }
    }
    
    /**
     * Check if we have an established session with a peer
     */
    fun hasEstablishedSession(peerID: String): Boolean {
        return sessionManager.hasEstablishedSession(peerID)
    }
    
    /**
     * Get session state for a peer (for UI state display)
     */
    fun getSessionState(peerID: String): NoiseSession.NoiseSessionState {
        return sessionManager.getSessionState(peerID)
    }
    
    // MARK: - Encryption/Decryption
    
    /**
     * Encrypt data for a specific peer using established Noise session
     */
    fun encrypt(data: ByteArray, peerID: String): ByteArray? {
        if (!hasEstablishedSession(peerID)) {
            Log.w(TAG, "No established session with $peerID, handshake required. TODO: IMPLEMENT HANDSHAKE INIT")
            onHandshakeRequired?.invoke(peerID)
            return null
        }
        
        return try {
            sessionManager.encrypt(data, peerID)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt for $peerID: ${e.message}")
            null
        }
    }
    
    /**
     * Decrypt data from a specific peer using established Noise session
     */
    fun decrypt(encryptedData: ByteArray, peerID: String): ByteArray? {
        if (!hasEstablishedSession(peerID)) {
            Log.w(TAG, "No established session with $peerID")
            return null
        }
        
        return try {
            sessionManager.decrypt(encryptedData, peerID)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt from $peerID: ${e.message}")
            null
        }
    }
    
    // MARK: - Peer Management
    
    /**
     * Get fingerprint for a peer (returns null if peer unknown)
     */
    fun getPeerFingerprint(peerID: String): String? {
        return fingerprintManager.getFingerprintForPeer(peerID)
    }
    
    /**
     * Get current peer ID for a fingerprint (returns null if not currently online)
     */
    fun getPeerID(fingerprint: String): String? {
        return fingerprintManager.getPeerIDForFingerprint(fingerprint)
    }
    
    /**
     * Remove a peer session (called when peer disconnects)
     */
    fun removePeer(peerID: String) {
        sessionManager.removeSession(peerID)
        
        // Clean up fingerprint mappings via centralized manager
        fingerprintManager.removePeer(peerID)
    }
    
    /**
     * Update peer ID mapping (for peer ID rotation)
     * This allows favorites/blocking to persist across peer ID changes
     */
    fun updatePeerIDMapping(oldPeerID: String?, newPeerID: String, fingerprint: String) {
        // Use centralized fingerprint manager for peer ID rotation
        fingerprintManager.updatePeerIDMapping(oldPeerID, newPeerID, fingerprint)
    }
    
    // MARK: - Channel Encryption
    
    /**
     * Set password for a channel (derives encryption key)
     */
    fun setChannelPassword(password: String, channel: String) {
        channelEncryption.setChannelPassword(password, channel)
    }
    
    /**
     * Encrypt message for a password-protected channel
     */
    fun encryptChannelMessage(message: String, channel: String): ByteArray? {
        return try {
            channelEncryption.encryptChannelMessage(message, channel)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt channel message for $channel: ${e.message}")
            null
        }
    }
    
    /**
     * Decrypt channel message
     */
    fun decryptChannelMessage(encryptedData: ByteArray, channel: String): String? {
        return try {
            channelEncryption.decryptChannelMessage(encryptedData, channel)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt channel message for $channel: ${e.message}")
            null
        }
    }
    
    /**
     * Remove channel password (when leaving channel)
     */
    fun removeChannelPassword(channel: String) {
        channelEncryption.removeChannelPassword(channel)
    }
    
    // MARK: - Session Maintenance
    
    /**
     * Get sessions that need rekey based on time or message count
     */
    fun getSessionsNeedingRekey(): List<String> {
        return sessionManager.getSessionsNeedingRekey()
    }
    
    /**
     * Initiate rekey for a session (replaces old session with new handshake)
     */
    fun initiateRekey(peerID: String): ByteArray? {
        Log.d(TAG, "Initiating rekey for session with $peerID")
        
        // Remove old session
        sessionManager.removeSession(peerID)
        
        // Start new handshake
        return initiateHandshake(peerID)
    }
    
    // MARK: - Private Helpers
    
    /**
     * Generate a new Curve25519 key pair using the real Noise library
     * Returns (privateKey, publicKey) as 32-byte arrays
     */
    private fun generateKeyPair(): Pair<ByteArray, ByteArray> {
        try {
            val dhState = com.bitchat.android.noise.southernstorm.protocol.Noise.createDH("25519")
            dhState.generateKeyPair()
            
            val privateKey = ByteArray(32)
            val publicKey = ByteArray(32)
            
            dhState.getPrivateKey(privateKey, 0)
            dhState.getPublicKey(publicKey, 0)
            
            dhState.destroy()
            
            return Pair(privateKey, publicKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate key pair: ${e.message}")
            throw e
        }
    }
    
    /**
     * Handle session establishment (called when Noise handshake completes)
     */
    private fun handleSessionEstablished(peerID: String, remoteStaticKey: ByteArray) {
        // Store fingerprint mapping via centralized manager
        // This is the ONLY place where fingerprints are stored - after successful Noise handshake
        fingerprintManager.storeFingerprintForPeer(peerID, remoteStaticKey)
        
        // Calculate fingerprint for logging and callback
        val fingerprint = calculateFingerprint(remoteStaticKey)
        
        Log.d(TAG, "Session established with $peerID, fingerprint: ${fingerprint.take(16)}...")
        
        // Notify about authentication
        onPeerAuthenticated?.invoke(peerID, fingerprint)
    }
    
    /**
     * Calculate fingerprint from public key (SHA-256 hash)
     */
    private fun calculateFingerprint(publicKey: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(publicKey)
        return hash.joinToString("") { "%02x".format(it) }
    }
    
    // MARK: - Packet Signing/Verification

    /**
     * Sign a BitchatPacket using our Ed25519 signing key
     */
    fun signPacket(packet: com.bitchat.android.protocol.BitchatPacket): com.bitchat.android.protocol.BitchatPacket? {
        // Create canonical packet bytes for signing
        val packetData = packet.toBinaryDataForSigning() ?: return null
        
        // Sign with our Ed25519 signing private key
        val signature = signData(packetData) ?: return null
        
        // Return new packet with signature
        return packet.copy(signature = signature)
    }

    /**
     * Verify a BitchatPacket signature using the provided public key
     */
    fun verifyPacketSignature(packet: com.bitchat.android.protocol.BitchatPacket, publicKey: ByteArray): Boolean {
        val signature = packet.signature ?: return false
        
        // Create canonical packet bytes for verification (without signature)
        val packetData = packet.toBinaryDataForSigning() ?: return false
        
        // Verify signature using the provided Ed25519 public key
        return verifySignature(signature, packetData, publicKey)
    }

    /**
     * Sign data with our Ed25519 signing key
     */
    fun signData(data: ByteArray): ByteArray? {
        return try {
            // For simplicity, we'll implement this using BouncyCastle which should be available
            // In a production system, you might want to use the Android Keystore
            signWithEd25519(data, signingPrivateKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sign data: ${e.message}")
            null
        }
    }

    /**
     * Verify signature with a public key
     */
    fun verifySignature(signature: ByteArray, data: ByteArray, publicKey: ByteArray): Boolean {
        return try {
            verifyWithEd25519(signature, data, publicKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to verify signature: ${e.message}")
            false
        }
    }

    /**
     * Generate a new Ed25519 key pair for signing using BouncyCastle
     * Returns (privateKey, publicKey) as 32-byte arrays
     */
    private fun generateEd25519KeyPair(): Pair<ByteArray, ByteArray> {
        try {
            // Use BouncyCastle for proper Ed25519 key generation
            val keyGen = org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator()
            keyGen.init(org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters(SecureRandom()))
            val keyPair = keyGen.generateKeyPair()
            
            val privateKey = (keyPair.private as org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters).encoded
            val publicKey = (keyPair.public as org.bouncycastle.crypto.params.Ed25519PublicKeyParameters).encoded
            
            return Pair(privateKey, publicKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate Ed25519 key pair: ${e.message}")
            throw e
        }
    }

    /**
     * Sign data with Ed25519 private key using BouncyCastle
     */
    private fun signWithEd25519(data: ByteArray, privateKey: ByteArray): ByteArray {
        try {
            val privateKeyParams = org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(privateKey, 0)
            val signer = org.bouncycastle.crypto.signers.Ed25519Signer()
            signer.init(true, privateKeyParams)
            signer.update(data, 0, data.size)
            return signer.generateSignature()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sign data with Ed25519: ${e.message}")
            throw e
        }
    }

    /**
     * Verify Ed25519 signature using BouncyCastle
     */
    private fun verifyWithEd25519(signature: ByteArray, data: ByteArray, publicKey: ByteArray): Boolean {
        try {
            val publicKeyParams = org.bouncycastle.crypto.params.Ed25519PublicKeyParameters(publicKey, 0)
            val verifier = org.bouncycastle.crypto.signers.Ed25519Signer()
            verifier.init(false, publicKeyParams)
            verifier.update(data, 0, data.size)
            return verifier.verifySignature(signature)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to verify Ed25519 signature: ${e.message}")
            return false
        }
    }

    /**
     * Clean shutdown
     */
    fun shutdown() {
        sessionManager.shutdown()
        channelEncryption.clear()
        // No need to clear fingerprints here - they are managed centrally
    }
}

/**
 * Noise-specific errors
 */
sealed class NoiseEncryptionError(message: String) : Exception(message) {
    object HandshakeRequired : NoiseEncryptionError("Handshake required before encryption")
    object SessionNotEstablished : NoiseEncryptionError("No established Noise session")
    object InvalidMessage : NoiseEncryptionError("Invalid message format")
    class HandshakeFailed(cause: Throwable) : NoiseEncryptionError("Handshake failed: ${cause.message}")
}
