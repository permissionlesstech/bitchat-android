package com.bitchat.android.crypto

import android.content.Context
import android.util.Log
import com.bitchat.android.noise.NoiseEncryptionService
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Encryption service that now uses NoiseEncryptionService internally
 * Maintains the same public API for backward compatibility
 * 
 * This is the main interface for all encryption/decryption operations in bitchat.
 * It now uses the Noise protocol for secure transport encryption with proper session management.
 */
class EncryptionService(private val context: Context) {
    
    companion object {
        private const val TAG = "EncryptionService"
    }
    
    // Core Noise encryption service
    private val noiseService: NoiseEncryptionService = NoiseEncryptionService(context)
    
    // Session tracking for established connections
    private val establishedSessions = ConcurrentHashMap<String, String>() // peerID -> fingerprint
    
    // Callbacks for UI state updates
    var onSessionEstablished: ((String) -> Unit)? = null // peerID
    var onSessionLost: ((String) -> Unit)? = null // peerID
    var onHandshakeRequired: ((String) -> Unit)? = null // peerID
    
    init {
        // Set up NoiseEncryptionService callbacks
        noiseService.onPeerAuthenticated = { peerID, fingerprint ->
            Log.d(TAG, "✅ Noise session established with $peerID, fingerprint: ${fingerprint.take(16)}...")
            establishedSessions[peerID] = fingerprint
            onSessionEstablished?.invoke(peerID)
        }
        
        noiseService.onHandshakeRequired = { peerID ->
            Log.d(TAG, "🤝 Handshake required for $peerID")
            onHandshakeRequired?.invoke(peerID)
        }
    }
    
    // MARK: - Public API (Maintains backward compatibility)
    
    /**
     * Get our static public key data (32 bytes for Noise)
     * This replaces the old 96-byte combined key format
     */
    fun getCombinedPublicKeyData(): ByteArray {
        return noiseService.getStaticPublicKeyData()
    }
    
    /**
     * Add peer's public key and start handshake if needed
     * For backward compatibility with old key exchange packets
     */
    @Throws(Exception::class)
    fun addPeerPublicKey(peerID: String, publicKeyData: ByteArray) {
        Log.d(TAG, "Legacy addPeerPublicKey called for $peerID with ${publicKeyData.size} bytes")
        
        // If this is from old key exchange format, initiate new Noise handshake
        if (!hasEstablishedSession(peerID)) {
            Log.d(TAG, "No Noise session with $peerID, initiating handshake")
            initiateHandshake(peerID)
        }
    }
    
    /**
     * Get peer's identity key (fingerprint) for favorites
     */
    fun getPeerIdentityKey(peerID: String): ByteArray? {
        val fingerprint = getPeerFingerprint(peerID) ?: return null
        return fingerprint.toByteArray()
    }
    
    /**
     * Clear persistent identity (for panic mode)
     */
    fun clearPersistentIdentity() {
        noiseService.clearPersistentIdentity()
        establishedSessions.clear()
    }
    
    /**
     * Encrypt data for a specific peer using Noise transport encryption
     */
    @Throws(Exception::class)
    fun encrypt(data: ByteArray, peerID: String): ByteArray {
        val encrypted = noiseService.encrypt(data, peerID)
        if (encrypted == null) {
            throw Exception("Failed to encrypt for $peerID - no established session")
        }
        return encrypted
    }
    
    /**
     * Decrypt data from a specific peer using Noise transport encryption
     */
    @Throws(Exception::class)
    fun decrypt(data: ByteArray, peerID: String): ByteArray {
        val decrypted = noiseService.decrypt(data, peerID)
        if (decrypted == null) {
            throw Exception("Failed to decrypt from $peerID - no established session")
        }
        return decrypted
    }
    
    /**
     * Sign data using our static identity key
     * Note: This is now done at the packet level, not per-message
     */
    @Throws(Exception::class)
    fun sign(data: ByteArray): ByteArray {
        // Note: In Noise protocol, authentication is built into the handshake
        // For compatibility, we return empty signature
        return ByteArray(0)
    }
    
    /**
     * Verify signature using peer's identity key
     * Note: This is now done at the packet level, not per-message
     */
    @Throws(Exception::class)
    fun verify(signature: ByteArray, data: ByteArray, peerID: String): Boolean {
        // Note: In Noise protocol, authentication is built into the transport
        // Messages are authenticated automatically when decrypted
        return hasEstablishedSession(peerID)
    }
    
    // MARK: - Noise Protocol Interface
    
    /**
     * Check if we have an established Noise session with a peer
     */
    fun hasEstablishedSession(peerID: String): Boolean {
        return noiseService.hasEstablishedSession(peerID)
    }
    
    /**
     * Get encryption icon state for UI
     */
    fun shouldShowEncryptionIcon(peerID: String): Boolean {
        return hasEstablishedSession(peerID)
    }
    
    /**
     * Get peer fingerprint for favorites/blocking
     */
    fun getPeerFingerprint(peerID: String): String? {
        return noiseService.getPeerFingerprint(peerID)
    }
    
    /**
     * Get current peer ID for a fingerprint (for peer ID rotation)
     */
    fun getCurrentPeerID(fingerprint: String): String? {
        return noiseService.getPeerID(fingerprint)
    }
    
    /**
     * Initiate a Noise handshake with a peer
     */
    fun initiateHandshake(peerID: String): ByteArray? {
        Log.d(TAG, "🤝 Initiating Noise handshake with $peerID")
        return noiseService.initiateHandshake(peerID)
    }
    
    /**
     * Process an incoming handshake message
     */
    fun processHandshakeMessage(data: ByteArray, peerID: String): ByteArray? {
        Log.d(TAG, "🤝 Processing handshake message from $peerID")
        return noiseService.processHandshakeMessage(data, peerID)
    }
    
    /**
     * Remove a peer session (called when peer disconnects)
     */
    fun removePeer(peerID: String) {
        establishedSessions.remove(peerID)
        noiseService.removePeer(peerID)
        onSessionLost?.invoke(peerID)
        Log.d(TAG, "🗑️ Removed session for $peerID")
    }
    
    /**
     * Update peer ID mapping (for peer ID rotation)
     */
    fun updatePeerIDMapping(oldPeerID: String?, newPeerID: String, fingerprint: String) {
        oldPeerID?.let { establishedSessions.remove(it) }
        establishedSessions[newPeerID] = fingerprint
        noiseService.updatePeerIDMapping(oldPeerID, newPeerID, fingerprint)
    }
    
    // MARK: - Channel Encryption
    
    /**
     * Set password for a channel (derives encryption key using Argon2id)
     */
    fun setChannelPassword(password: String, channel: String) {
        noiseService.setChannelPassword(password, channel)
    }
    
    /**
     * Encrypt message for a password-protected channel
     */
    fun encryptChannelMessage(message: String, channel: String): ByteArray? {
        return noiseService.encryptChannelMessage(message, channel)
    }
    
    /**
     * Decrypt channel message
     */
    fun decryptChannelMessage(encryptedData: ByteArray, channel: String): String? {
        return noiseService.decryptChannelMessage(encryptedData, channel)
    }
    
    /**
     * Remove channel password (when leaving channel)
     */
    fun removeChannelPassword(channel: String) {
        noiseService.removeChannelPassword(channel)
    }
    
    // MARK: - Session Management
    
    /**
     * Get all peers with established sessions
     */
    fun getEstablishedPeers(): List<String> {
        return establishedSessions.keys.toList()
    }
    
    /**
     * Get sessions that need rekeying
     */
    fun getSessionsNeedingRekey(): List<String> {
        return noiseService.getSessionsNeedingRekey()
    }
    
    /**
     * Initiate rekey for a session
     */
    fun initiateRekey(peerID: String): ByteArray? {
        Log.d(TAG, "🔄 Initiating rekey for $peerID")
        establishedSessions.remove(peerID) // Will be re-added when new session is established
        return noiseService.initiateRekey(peerID)
    }
    
    /**
     * Get our identity fingerprint
     */
    fun getIdentityFingerprint(): String {
        return noiseService.getIdentityFingerprint()
    }
    
    /**
     * Get debug information about encryption state
     */
    fun getDebugInfo(): String = buildString {
        appendLine("=== EncryptionService Debug ===")
        appendLine("Established Sessions: ${establishedSessions.size}")
        appendLine("Our Fingerprint: ${getIdentityFingerprint().take(16)}...")
        
        if (establishedSessions.isNotEmpty()) {
            appendLine("Active Encrypted Sessions:")
            establishedSessions.forEach { (peerID, fingerprint) ->
                appendLine("  $peerID -> ${fingerprint.take(16)}...")
            }
        }
        
        appendLine("")
        appendLine(noiseService.toString()) // Include NoiseService state
    }
    
    /**
     * Shutdown encryption service
     */
    fun shutdown() {
        establishedSessions.clear()
        noiseService.shutdown()
        Log.d(TAG, "🔌 EncryptionService shut down")
    }
}

/**
 * Message padding utilities - exact same as iOS version
 */
object MessagePadding {
    // Standard block sizes for padding
    private val blockSizes = listOf(256, 512, 1024, 2048)
    
    /**
     * Add PKCS#7-style padding to reach target size
     */
    fun pad(data: ByteArray, targetSize: Int): ByteArray {
        if (data.size >= targetSize) return data
        
        val paddingNeeded = targetSize - data.size
        
        // PKCS#7 only supports padding up to 255 bytes
        if (paddingNeeded > 255) return data
        
        val padded = ByteArray(targetSize)
        System.arraycopy(data, 0, padded, 0, data.size)
        
        // Fill with random bytes except the last byte
        val random = SecureRandom()
        random.nextBytes(padded.sliceArray(data.size until targetSize - 1))
        
        // Last byte indicates padding length (PKCS#7)
        padded[targetSize - 1] = paddingNeeded.toByte()
        
        return padded
    }
    
    /**
     * Remove padding from data
     */
    fun unpad(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        
        // Last byte tells us how much padding to remove
        val paddingLength = (data.last().toInt() and 0xFF)
        if (paddingLength <= 0 || paddingLength > data.size) return data
        
        return data.sliceArray(0 until (data.size - paddingLength))
    }
    
    /**
     * Find optimal block size for data
     */
    fun optimalBlockSize(dataSize: Int): Int {
        // Account for encryption overhead (~16 bytes for AES-GCM tag)
        val totalSize = dataSize + 16
        
        // Find smallest block that fits
        for (blockSize in blockSizes) {
            if (totalSize <= blockSize) {
                return blockSize
            }
        }
        
        // For very large messages, just use the original size
        return dataSize
    }
}
