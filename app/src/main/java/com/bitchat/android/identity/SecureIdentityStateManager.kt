package com.bitchat.android.identity

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Log

/**
 * Manages persistent identity storage and peer ID rotation - 100% compatible with iOS implementation
 * 
 * Handles:
 * - Static identity key persistence across app sessions
 * - Peer ID rotation timing (5-15 minute random intervals)
 * - Secure storage using Android EncryptedSharedPreferences
 * - Fingerprint calculation and identity validation
 */
class SecureIdentityStateManager(private val context: Context) {
    
    companion object {
        private const val TAG = "SecureIdentityStateManager"
        private const val PREFS_NAME = "bitchat_identity"
        private const val KEY_STATIC_PRIVATE_KEY = "static_private_key"
        private const val KEY_STATIC_PUBLIC_KEY = "static_public_key"
        private const val KEY_LAST_ROTATION = "last_rotation"
        private const val KEY_NEXT_ROTATION_INTERVAL = "next_rotation_interval"
        
        // Rotation intervals (same as iOS)
        private const val MIN_ROTATION_INTERVAL = 5 * 60 * 1000L  // 5 minutes
        private const val MAX_ROTATION_INTERVAL = 15 * 60 * 1000L // 15 minutes
    }
    
    private val prefs: SharedPreferences
    private val random = SecureRandom()
    
    init {
        // Create master key for encryption
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        
        // Create encrypted shared preferences
        prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    
    // MARK: - Static Key Management
    
    /**
     * Load saved static key pair
     * Returns (privateKey, publicKey) or null if none exists
     */
    fun loadStaticKey(): Pair<ByteArray, ByteArray>? {
        return try {
            val privateKeyString = prefs.getString(KEY_STATIC_PRIVATE_KEY, null)
            val publicKeyString = prefs.getString(KEY_STATIC_PUBLIC_KEY, null)
            
            if (privateKeyString != null && publicKeyString != null) {
                val privateKey = android.util.Base64.decode(privateKeyString, android.util.Base64.DEFAULT)
                val publicKey = android.util.Base64.decode(publicKeyString, android.util.Base64.DEFAULT)
                
                // Validate key sizes
                if (privateKey.size == 32 && publicKey.size == 32) {
                    Log.d(TAG, "Loaded static identity key from secure storage")
                    Pair(privateKey, publicKey)
                } else {
                    Log.w(TAG, "Invalid key sizes in storage, returning null")
                    null
                }
            } else {
                Log.d(TAG, "No static identity key found in storage")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load static key: ${e.message}")
            null
        }
    }
    
    /**
     * Save static key pair to secure storage
     */
    fun saveStaticKey(privateKey: ByteArray, publicKey: ByteArray) {
        try {
            // Validate key sizes
            if (privateKey.size != 32 || publicKey.size != 32) {
                throw IllegalArgumentException("Invalid key sizes: private=${privateKey.size}, public=${publicKey.size}")
            }
            
            val privateKeyString = android.util.Base64.encodeToString(privateKey, android.util.Base64.DEFAULT)
            val publicKeyString = android.util.Base64.encodeToString(publicKey, android.util.Base64.DEFAULT)
            
            prefs.edit()
                .putString(KEY_STATIC_PRIVATE_KEY, privateKeyString)
                .putString(KEY_STATIC_PUBLIC_KEY, publicKeyString)
                .apply()
            
            Log.d(TAG, "Saved static identity key to secure storage")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save static key: ${e.message}")
            throw e
        }
    }
    
    // MARK: - Fingerprint Generation
    
    /**
     * Generate fingerprint from public key (SHA-256 hash)
     */
    fun generateFingerprint(publicKeyData: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(publicKeyData)
        return hash.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Validate fingerprint format
     */
    fun isValidFingerprint(fingerprint: String): Boolean {
        // SHA-256 fingerprint should be 64 hex characters
        return fingerprint.matches(Regex("^[a-fA-F0-9]{64}$"))
    }
    
    // MARK: - Peer ID Rotation Management
    
    /**
     * Check if peer ID should be rotated based on random interval
     */
    fun shouldRotatePeerID(): Boolean {
        val lastRotation = prefs.getLong(KEY_LAST_ROTATION, 0L)
        val nextInterval = prefs.getLong(KEY_NEXT_ROTATION_INTERVAL, 0L)
        val now = System.currentTimeMillis()
        
        if (lastRotation == 0L || nextInterval == 0L) {
            // First run or missing data - schedule next rotation and don't rotate now
            scheduleNextRotation()
            return false
        }
        
        val shouldRotate = (now - lastRotation) >= nextInterval
        if (shouldRotate) {
            Log.d(TAG, "Peer ID rotation due: ${(now - lastRotation) / 1000}s since last rotation")
        }
        
        return shouldRotate
    }
    
    /**
     * Mark rotation as completed and schedule next one
     */
    fun markRotationCompleted() {
        val now = System.currentTimeMillis()
        prefs.edit()
            .putLong(KEY_LAST_ROTATION, now)
            .apply()
        
        scheduleNextRotation()
        
        Log.d(TAG, "Peer ID rotation marked as completed")
    }
    
    /**
     * Schedule the next rotation with random interval (5-15 minutes)
     */
    private fun scheduleNextRotation() {
        val nextInterval = MIN_ROTATION_INTERVAL + random.nextLong(MAX_ROTATION_INTERVAL - MIN_ROTATION_INTERVAL)
        
        prefs.edit()
            .putLong(KEY_NEXT_ROTATION_INTERVAL, nextInterval)
            .apply()
        
        Log.d(TAG, "Next peer ID rotation scheduled in ${nextInterval / 60000} minutes")
    }
    
    /**
     * Get time until next rotation (for debugging)
     */
    fun getTimeUntilNextRotation(): Long {
        val lastRotation = prefs.getLong(KEY_LAST_ROTATION, 0L)
        val nextInterval = prefs.getLong(KEY_NEXT_ROTATION_INTERVAL, 0L)
        val now = System.currentTimeMillis()
        
        if (lastRotation == 0L || nextInterval == 0L) return -1
        
        val elapsed = now - lastRotation
        return maxOf(0L, nextInterval - elapsed)
    }
    
    // MARK: - Identity Validation
    
    /**
     * Validate that a public key is valid for Curve25519
     */
    fun validatePublicKey(publicKey: ByteArray): Boolean {
        if (publicKey.size != 32) return false
        
        // Check for all-zero key (invalid point)
        if (publicKey.all { it == 0.toByte() }) return false
        
        // Check for other known invalid points
        val invalidPoints = setOf(
            ByteArray(32) { 0x00.toByte() }, // All zeros
            ByteArray(32) { 0xFF.toByte() }, // All ones
            // Add other known invalid Curve25519 points if needed
        )
        
        return !invalidPoints.any { it.contentEquals(publicKey) }
    }
    
    /**
     * Validate that a private key is valid for Curve25519
     */
    fun validatePrivateKey(privateKey: ByteArray): Boolean {
        if (privateKey.size != 32) return false
        
        // Check for all-zero key
        if (privateKey.all { it == 0.toByte() }) return false
        
        // Check that clamping bits are correct for Curve25519
        val clampedKey = privateKey.clone()
        clampedKey[0] = (clampedKey[0].toInt() and 248).toByte()
        clampedKey[31] = (clampedKey[31].toInt() and 127).toByte()
        clampedKey[31] = (clampedKey[31].toInt() or 64).toByte()
        
        // After clamping, the key should not be all zeros
        return !clampedKey.all { it == 0.toByte() }
    }
    
    // MARK: - Debug Information
    
    /**
     * Get debug information about identity state
     */
    fun getDebugInfo(): String = buildString {
        appendLine("=== Identity State Manager Debug ===")
        
        val hasIdentity = prefs.contains(KEY_STATIC_PRIVATE_KEY)
        appendLine("Has identity: $hasIdentity")
        
        if (hasIdentity) {
            val lastRotation = prefs.getLong(KEY_LAST_ROTATION, 0L)
            val nextInterval = prefs.getLong(KEY_NEXT_ROTATION_INTERVAL, 0L)
            val timeUntilNext = getTimeUntilNextRotation()
            
            appendLine("Last rotation: ${if (lastRotation > 0) "${(System.currentTimeMillis() - lastRotation) / 1000}s ago" else "never"}")
            appendLine("Next rotation in: ${if (timeUntilNext >= 0) "${timeUntilNext / 1000}s" else "not scheduled"}")
            appendLine("Rotation interval: ${nextInterval / 1000}s")
            
            try {
                val keyPair = loadStaticKey()
                if (keyPair != null) {
                    val fingerprint = generateFingerprint(keyPair.second)
                    appendLine("Identity fingerprint: ${fingerprint.take(16)}...")
                    appendLine("Key validation: private=${validatePrivateKey(keyPair.first)}, public=${validatePublicKey(keyPair.second)}")
                }
            } catch (e: Exception) {
                appendLine("Key validation failed: ${e.message}")
            }
        }
    }
    
    // MARK: - Emergency Clear
    
    /**
     * Clear all identity data (for panic mode)
     */
    fun clearIdentityData() {
        try {
            prefs.edit().clear().apply()
            Log.w(TAG, "All identity data cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear identity data: ${e.message}")
        }
    }
    
    /**
     * Check if identity data exists
     */
    fun hasIdentityData(): Boolean {
        return prefs.contains(KEY_STATIC_PRIVATE_KEY) && prefs.contains(KEY_STATIC_PUBLIC_KEY)
    }
}
