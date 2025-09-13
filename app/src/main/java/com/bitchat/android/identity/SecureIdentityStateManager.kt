package com.bitchat.android.identity

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import android.util.Log

/**
 * Manages persistent identity storage and peer ID rotation - 100% compatible with iOS implementation
 * 
 * Handles:
 * - Static identity key persistence across app sessions
 * - Secure storage using Android EncryptedSharedPreferences
 * - Fingerprint calculation and identity validation
 */
class SecureIdentityStateManager(private val context: Context) {
    
    companion object {
        private const val TAG = "SecureIdentityStateManager"
        private const val PREFS_NAME = "bitchat_identity"
        private const val KEY_STATIC_PRIVATE_KEY = "static_private_key"
        private const val KEY_STATIC_PUBLIC_KEY = "static_public_key"
        private const val KEY_SIGNING_PRIVATE_KEY = "signing_private_key"
        private const val KEY_SIGNING_PUBLIC_KEY = "signing_public_key"
    }
    
    private val prefs: SharedPreferences
    
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

    // MARK: - Signing Key Management

    /**
     * Load saved signing key pair
     * Returns (privateKey, publicKey) or null if none exists
     */
    fun loadSigningKey(): Pair<ByteArray, ByteArray>? {
        return try {
            val privateKeyString = prefs.getString(KEY_SIGNING_PRIVATE_KEY, null)
            val publicKeyString = prefs.getString(KEY_SIGNING_PUBLIC_KEY, null)
            
            if (privateKeyString != null && publicKeyString != null) {
                val privateKey = android.util.Base64.decode(privateKeyString, android.util.Base64.DEFAULT)
                val publicKey = android.util.Base64.decode(publicKeyString, android.util.Base64.DEFAULT)
                
                // Validate key sizes
                if (privateKey.size == 32 && publicKey.size == 32) {
                    Log.d(TAG, "Loaded Ed25519 signing key from secure storage")
                    Pair(privateKey, publicKey)
                } else {
                    Log.w(TAG, "Invalid signing key sizes in storage, returning null")
                    null
                }
            } else {
                Log.d(TAG, "No Ed25519 signing key found in storage")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load signing key: ${e.message}")
            null
        }
    }

    /**
     * Save signing key pair to secure storage
     */
    fun saveSigningKey(privateKey: ByteArray, publicKey: ByteArray) {
        try {
            // Validate key sizes
            if (privateKey.size != 32 || publicKey.size != 32) {
                throw IllegalArgumentException("Invalid signing key sizes: private=${privateKey.size}, public=${publicKey.size}")
            }
            
            val privateKeyString = android.util.Base64.encodeToString(privateKey, android.util.Base64.DEFAULT)
            val publicKeyString = android.util.Base64.encodeToString(publicKey, android.util.Base64.DEFAULT)
            
            prefs.edit()
                .putString(KEY_SIGNING_PRIVATE_KEY, privateKeyString)
                .putString(KEY_SIGNING_PUBLIC_KEY, publicKeyString)
                .apply()
            
            Log.d(TAG, "Saved Ed25519 signing key to secure storage")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save signing key: ${e.message}")
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
    
    // MARK: - Peer ID Rotation Management (removed)
    // Android now derives peer ID from the persisted Noise identity fingerprint.
    // No timed peer ID rotation is performed here.
    
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
    
    // MARK: - Public SharedPreferences Access (for favorites and Nostr data)
    
    /**
     * Store a string value in secure preferences
     */
    fun storeSecureValue(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
    
    /**
     * Retrieve a string value from secure preferences
     */
    fun getSecureValue(key: String): String? {
        return prefs.getString(key, null)
    }
    
    /**
     * Remove a value from secure preferences
     */
    fun removeSecureValue(key: String) {
        prefs.edit().remove(key).apply()
    }
    
    /**
     * Check if a key exists in secure preferences
     */
    fun hasSecureValue(key: String): Boolean {
        return prefs.contains(key)
    }
    
    /**
     * Clear specific keys from secure preferences
     */
    fun clearSecureValues(vararg keys: String) {
        val editor = prefs.edit()
        keys.forEach { key ->
            editor.remove(key)
        }
        editor.apply()
    }
}
