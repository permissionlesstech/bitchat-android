package com.bitchat.android.nostr

import android.content.Context
import android.util.Log
import com.bitchat.android.identity.SecureIdentityStateManager
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Manages Nostr identity (secp256k1 keypair) for NIP-17 private messaging
 * Compatible with iOS implementation
 */
data class NostrIdentity(
    val privateKeyHex: String,
    val publicKeyHex: String,
    val npub: String,
    val createdAt: Long
) {
    
    companion object {
        private const val TAG = "NostrIdentity"
        
        /**
         * Generate a new Nostr identity
         */
        fun generate(): NostrIdentity {
            val (privateKeyHex, publicKeyHex) = NostrCrypto.generateKeyPair()
            val npub = Bech32.encode("npub", publicKeyHex.hexToByteArray())
            
            Log.d(TAG, "Generated new Nostr identity: npub=$npub")
            
            return NostrIdentity(
                privateKeyHex = privateKeyHex,
                publicKeyHex = publicKeyHex,
                npub = npub,
                createdAt = System.currentTimeMillis()
            )
        }
        
        /**
         * Create from existing private key
         */
        fun fromPrivateKey(privateKeyHex: String): NostrIdentity {
            require(NostrCrypto.isValidPrivateKey(privateKeyHex)) { 
                "Invalid private key" 
            }
            
            val publicKeyHex = NostrCrypto.derivePublicKey(privateKeyHex)
            val npub = Bech32.encode("npub", publicKeyHex.hexToByteArray())
            
            return NostrIdentity(
                privateKeyHex = privateKeyHex,
                publicKeyHex = publicKeyHex,
                npub = npub,
                createdAt = System.currentTimeMillis()
            )
        }
    }
    
    /**
     * Sign a Nostr event
     */
    fun signEvent(event: NostrEvent): NostrEvent {
        return event.sign(privateKeyHex)
    }
    
    /**
     * Get short display format
     */
    fun getShortNpub(): String {
        return if (npub.length > 16) {
            "${npub.take(8)}...${npub.takeLast(8)}"
        } else {
            npub
        }
    }
}

/**
 * Bridge between Noise and Nostr identities
 * Manages persistent storage and per-geohash identity derivation
 */
object NostrIdentityBridge {
    private const val TAG = "NostrIdentityBridge"
    private const val NOSTR_PRIVATE_KEY = "nostr_private_key"
    private const val DEVICE_SEED_KEY = "nostr_device_seed"
    
    /**
     * Get or create the current Nostr identity
     */
    fun getCurrentNostrIdentity(context: Context): NostrIdentity? {
        val stateManager = SecureIdentityStateManager(context)
        
        // Try to load existing Nostr private key
        val existingKey = loadNostrPrivateKey(stateManager)
        if (existingKey != null) {
            return try {
                NostrIdentity.fromPrivateKey(existingKey)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create identity from stored key: ${e.message}")
                null
            }
        }
        
        // Generate new identity
        val newIdentity = NostrIdentity.generate()
        saveNostrPrivateKey(stateManager, newIdentity.privateKeyHex)
        
        Log.i(TAG, "Created new Nostr identity: ${newIdentity.getShortNpub()}")
        return newIdentity
    }
    
    /**
     * Derive a deterministic, unlinkable Nostr identity for a given geohash
     * Uses HMAC-SHA256(deviceSeed, geohash) as private key material
     */
    fun deriveIdentity(forGeohash: String, context: Context): NostrIdentity {
        val stateManager = SecureIdentityStateManager(context)
        val seed = getOrCreateDeviceSeed(stateManager)
        
        val geohashBytes = forGeohash.toByteArray(Charsets.UTF_8)
        
        // Try different iterations to ensure valid secp256k1 private key
        for (iteration in 0 until 10) {
            val input = geohashBytes + iteration.toString().toByteArray()
            val candidateKey = hmacSha256(seed, input)
            
            val candidateKeyHex = candidateKey.toHexString()
            if (NostrCrypto.isValidPrivateKey(candidateKeyHex)) {
                return NostrIdentity.fromPrivateKey(candidateKeyHex)
            }
        }
        
        // Fallback: hash seed + geohash
        val combined = seed + geohashBytes
        val digest = MessageDigest.getInstance("SHA-256")
        val fallbackKey = digest.digest(combined)
        
        return NostrIdentity.fromPrivateKey(fallbackKey.toHexString())
    }
    
    /**
     * Associate a Nostr identity with a Noise public key (for favorites)
     */
    fun associateNostrIdentity(nostrPubkey: String, noisePublicKey: ByteArray, context: Context) {
        val stateManager = SecureIdentityStateManager(context)
        
        // We'll use the existing signing key storage mechanism for associations
        // For now, we'll store this as a preference since it's just for favorites mapping
        // In a full implementation, you'd want a proper association storage system
        
        Log.d(TAG, "Associated Nostr pubkey ${nostrPubkey.take(16)}... with Noise key")
    }
    
    /**
     * Get Nostr public key associated with a Noise public key
     */
    fun getNostrPublicKey(noisePublicKey: ByteArray, context: Context): String? {
        // This would need proper implementation based on your favorites storage system
        // For now, return null as we don't have the full association system
        return null
    }
    
    /**
     * Clear all Nostr identity data
     */
    fun clearAllAssociations(context: Context) {
        val stateManager = SecureIdentityStateManager(context)
        
        // Clear Nostr private key
        try {
            val clazz = stateManager.javaClass
            val prefsField = clazz.getDeclaredField("prefs")
            prefsField.isAccessible = true
            val prefs = prefsField.get(stateManager) as android.content.SharedPreferences
            
            prefs.edit()
                .remove(NOSTR_PRIVATE_KEY)
                .remove(DEVICE_SEED_KEY)
                .apply()
                
            Log.i(TAG, "Cleared all Nostr identity data")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear Nostr data: ${e.message}")
        }
    }
    
    // MARK: - Private Methods
    
    private fun loadNostrPrivateKey(stateManager: SecureIdentityStateManager): String? {
        return try {
            // Use reflection to access the encrypted preferences
            val clazz = stateManager.javaClass
            val prefsField = clazz.getDeclaredField("prefs")
            prefsField.isAccessible = true
            val prefs = prefsField.get(stateManager) as android.content.SharedPreferences
            
            prefs.getString(NOSTR_PRIVATE_KEY, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Nostr private key: ${e.message}")
            null
        }
    }
    
    private fun saveNostrPrivateKey(stateManager: SecureIdentityStateManager, privateKeyHex: String) {
        try {
            // Use reflection to access the encrypted preferences
            val clazz = stateManager.javaClass
            val prefsField = clazz.getDeclaredField("prefs")
            prefsField.isAccessible = true
            val prefs = prefsField.get(stateManager) as android.content.SharedPreferences
            
            prefs.edit()
                .putString(NOSTR_PRIVATE_KEY, privateKeyHex)
                .apply()
                
            Log.d(TAG, "Saved Nostr private key to secure storage")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save Nostr private key: ${e.message}")
            throw e
        }
    }
    
    private fun getOrCreateDeviceSeed(stateManager: SecureIdentityStateManager): ByteArray {
        try {
            // Use reflection to access the encrypted preferences
            val clazz = stateManager.javaClass
            val prefsField = clazz.getDeclaredField("prefs")
            prefsField.isAccessible = true
            val prefs = prefsField.get(stateManager) as android.content.SharedPreferences
            
            val existingSeed = prefs.getString(DEVICE_SEED_KEY, null)
            if (existingSeed != null) {
                return android.util.Base64.decode(existingSeed, android.util.Base64.DEFAULT)
            }
            
            // Generate new seed
            val seed = ByteArray(32)
            SecureRandom().nextBytes(seed)
            
            val seedBase64 = android.util.Base64.encodeToString(seed, android.util.Base64.DEFAULT)
            prefs.edit()
                .putString(DEVICE_SEED_KEY, seedBase64)
                .apply()
                
            Log.d(TAG, "Generated new device seed for geohash identity derivation")
            return seed
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get/create device seed: ${e.message}")
            throw e
        }
    }
    
    private fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        val secretKeySpec = javax.crypto.spec.SecretKeySpec(key, "HmacSHA256")
        mac.init(secretKeySpec)
        return mac.doFinal(message)
    }
}
