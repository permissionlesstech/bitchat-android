package com.bitchat.android.noise

import android.util.Log
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Channel encryption for password-protected channels - 100% compatible with iOS implementation
 * 
 * Uses PBKDF2 key derivation with channel name as salt and AES-256-GCM for encryption.
 * This is separate from Noise sessions and used for group channels with shared passwords.
 */
class NoiseChannelEncryption {
    
    companion object {
        private const val TAG = "NoiseChannelEncryption"
        
        // PBKDF2 parameters (same as iOS)
        private const val PBKDF2_ITERATIONS = 100000
        private const val KEY_LENGTH = 256 // 256-bit AES key
    }
    
    // Channel keys storage (channelName -> AES key)
    private val channelKeys = ConcurrentHashMap<String, SecretKeySpec>()
    
    // Channel passwords (for rekey operations)
    private val channelPasswords = ConcurrentHashMap<String, String>()
    
    // MARK: - Channel Password Management
    
    /**
     * Set password for a channel and derive encryption key
     */
    fun setChannelPassword(password: String, channel: String) {
        try {
            if (password.isEmpty()) {
                Log.w(TAG, "Empty password provided for channel $channel")
                return
            }
            
            // Derive key from password using PBKDF2 (same as iOS)
            val key = deriveChannelKey(password, channel)
            
            // Store key and password
            channelKeys[channel] = key
            channelPasswords[channel] = password
            
            Log.d(TAG, "Set password for channel $channel")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set password for channel $channel: ${e.message}")
        }
    }
    
    /**
     * Remove password for a channel
     */
    fun removeChannelPassword(channel: String) {
        channelKeys.remove(channel)
        channelPasswords.remove(channel)
        Log.d(TAG, "Removed password for channel $channel")
    }
    
    /**
     * Check if we have a key for a channel
     */
    fun hasChannelKey(channel: String): Boolean {
        return channelKeys.containsKey(channel)
    }
    
    /**
     * Get channel password (if available)
     */
    fun getChannelPassword(channel: String): String? {
        return channelPasswords[channel]
    }
    
    // MARK: - Encryption/Decryption
    
    /**
     * Encrypt a message for a channel
     * Returns encrypted data including IV
     */
    fun encryptChannelMessage(message: String, channel: String): ByteArray {
        val key = channelKeys[channel]
            ?: throw IllegalStateException("No key available for channel $channel")
        
        val messageBytes = message.toByteArray(Charsets.UTF_8)
        
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            
            val iv = cipher.iv
            val encryptedData = cipher.doFinal(messageBytes)
            
            // Combine IV and encrypted data (same format as iOS)
            val result = ByteArray(iv.size + encryptedData.size)
            System.arraycopy(iv, 0, result, 0, iv.size)
            System.arraycopy(encryptedData, 0, result, iv.size, encryptedData.size)
            
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt channel message: ${e.message}")
            throw e
        }
    }
    
    /**
     * Decrypt a message for a channel
     * Expects data format: IV + encrypted_data + auth_tag
     */
    fun decryptChannelMessage(encryptedData: ByteArray, channel: String): String {
        val key = channelKeys[channel]
            ?: throw IllegalStateException("No key available for channel $channel")
        
        if (encryptedData.size < 16) { // 12 bytes IV + minimum ciphertext
            throw IllegalArgumentException("Encrypted data too short")
        }
        
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            
            // Extract IV (first 12 bytes for GCM) and ciphertext
            val iv = encryptedData.sliceArray(0..11)
            val ciphertext = encryptedData.sliceArray(12 until encryptedData.size)
            
            val gcmSpec = GCMParameterSpec(128, iv) // 128-bit authentication tag
            cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)
            
            val decryptedBytes = cipher.doFinal(ciphertext)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt channel message: ${e.message}")
            throw e
        }
    }
    
    // MARK: - Key Derivation
    
    /**
     * Derive AES key from password using PBKDF2 (same parameters as iOS)
     */
    private fun deriveChannelKey(password: String, channel: String): SecretKeySpec {
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            
            // Use channel name as salt (UTF-8 bytes)
            val salt = channel.toByteArray(Charsets.UTF_8)
            
            val spec = PBEKeySpec(
                password.toCharArray(),
                salt,
                PBKDF2_ITERATIONS,
                KEY_LENGTH
            )
            
            val secretKey = factory.generateSecret(spec)
            return SecretKeySpec(secretKey.encoded, "AES")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to derive channel key: ${e.message}")
            throw e
        }
    }
    
    // MARK: - Key Verification
    
    /**
     * Calculate key commitment (SHA-256 hash) for verification
     * This allows peers to verify they have the same key without revealing it
     */
    fun calculateKeyCommitment(channel: String): String? {
        val key = channelKeys[channel] ?: return null
        
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(key.encoded)
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to calculate key commitment: ${e.message}")
            null
        }
    }
    
    /**
     * Verify key commitment matches our derived key
     */
    fun verifyKeyCommitment(channel: String, commitment: String): Boolean {
        val ourCommitment = calculateKeyCommitment(channel)
        return ourCommitment?.lowercase() == commitment.lowercase()
    }
    
    // MARK: - Channel Key Sharing
    
    /**
     * Create channel key packet for sharing via Noise session
     * Returns encrypted packet that can be sent to other peers
     */
    fun createChannelKeyPacket(password: String, channel: String): ByteArray? {
        return try {
            // Create key packet with channel and password
            val packet = mapOf(
                "channel" to channel,
                "password" to password,
                "timestamp" to System.currentTimeMillis()
            )
            
            // Simple JSON encoding for now (could be replaced with more efficient format)
            val json = com.google.gson.Gson().toJson(packet)
            json.toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create channel key packet: ${e.message}")
            null
        }
    }
    
    /**
     * Process received channel key packet
     * Returns (channel, password) if successful
     */
    fun processChannelKeyPacket(data: ByteArray): Pair<String, String>? {
        return try {
            val json = String(data, Charsets.UTF_8)
            val packet = com.google.gson.Gson().fromJson(json, Map::class.java) as Map<String, Any>
            
            val channel = packet["channel"] as? String
            val password = packet["password"] as? String
            
            if (channel != null && password != null) {
                Pair(channel, password)
            } else {
                Log.w(TAG, "Invalid channel key packet format")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process channel key packet: ${e.message}")
            null
        }
    }
    
    // MARK: - Debug and Management
    
    /**
     * Get debug information
     */
    fun getDebugInfo(): String = buildString {
        appendLine("=== Channel Encryption Debug ===")
        appendLine("Active channels: ${channelKeys.size}")
        
        channelKeys.keys.forEach { channel ->
            val hasPassword = channelPasswords.containsKey(channel)
            val commitment = calculateKeyCommitment(channel)?.take(16)
            appendLine("  $channel: hasPassword=$hasPassword, commitment=${commitment}...")
        }
    }
    
    /**
     * Get list of channels with keys
     */
    fun getActiveChannels(): Set<String> {
        return channelKeys.keys.toSet()
    }
    
    /**
     * Clear all channel data
     */
    fun clear() {
        channelKeys.clear()
        channelPasswords.clear()
        Log.d(TAG, "Cleared all channel encryption data")
    }
}
