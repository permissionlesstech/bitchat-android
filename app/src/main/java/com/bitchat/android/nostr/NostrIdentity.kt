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
            val npub = Bech32.encode("npub", publicKeyHex.hexToByteArrayLocal())
            
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
            val npub = Bech32.encode("npub", publicKeyHex.hexToByteArrayLocal())
            
            return NostrIdentity(
                privateKeyHex = privateKeyHex,
                publicKeyHex = publicKeyHex,
                npub = npub,
                createdAt = System.currentTimeMillis()
            )
        }
        
        /**
         * Create from a deterministic seed (for demo purposes)
         */
        fun fromSeed(seed: String): NostrIdentity {
            // Hash the seed to create a private key
            val digest = MessageDigest.getInstance("SHA-256")
            val seedBytes = seed.toByteArray(Charsets.UTF_8)
            val privateKeyBytes = digest.digest(seedBytes)
            val privateKeyHex = privateKeyBytes.joinToString("") { "%02x".format(it) }
            
            return fromPrivateKey(privateKeyHex)
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
    
    // Cache for derived geohash identities to avoid repeated crypto operations
    private val geohashIdentityCache = mutableMapOf<String, NostrIdentity>()
    
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
     * Uses HMAC-SHA256(deviceSeed, geohash) as private key material with fallback rehashing
     * if the candidate is not a valid secp256k1 private key.
     * 
     * Direct port from iOS implementation for 100% compatibility
     * OPTIMIZED: Cached for UI responsiveness
     */
    fun deriveIdentity(forGeohash: String, context: Context): NostrIdentity {
        // Check cache first for immediate response
        geohashIdentityCache[forGeohash]?.let { cachedIdentity ->
            //Log.v(TAG, "Using cached geohash identity for $forGeohash")
            return cachedIdentity
        }
        
        val stateManager = SecureIdentityStateManager(context)
        val seed = getOrCreateDeviceSeed(stateManager)
        
        val geohashBytes = forGeohash.toByteArray(Charsets.UTF_8)
        
        // Try a few iterations to ensure a valid key can be formed (exactly like iOS)
        for (i in 0 until 10) {
            val candidateKey = candidateKey(seed, geohashBytes, i.toUInt())
            val candidateKeyHex = candidateKey.toHexStringLocal()
            
            if (NostrCrypto.isValidPrivateKey(candidateKeyHex)) {
                val identity = NostrIdentity.fromPrivateKey(candidateKeyHex)
                
                // Cache the result for future UI responsiveness
                geohashIdentityCache[forGeohash] = identity
                
                Log.d(TAG, "Derived geohash identity for $forGeohash (iteration $i)")
                return identity
            }
        }
        
        // As a final fallback, hash the seed+msg and try again (exactly like iOS)
        val combined = seed + geohashBytes
        val digest = MessageDigest.getInstance("SHA-256")
        val fallbackKey = digest.digest(combined)
        
        val fallbackIdentity = NostrIdentity.fromPrivateKey(fallbackKey.toHexStringLocal())
        
        // Cache the fallback result too
        geohashIdentityCache[forGeohash] = fallbackIdentity
        
        Log.d(TAG, "Used fallback identity derivation for $forGeohash")
        return fallbackIdentity
    }
    
    /**
     * Generate candidate key for a specific iteration (matches iOS implementation)
     */
    private fun candidateKey(seed: ByteArray, message: ByteArray, iteration: UInt): ByteArray {
        val input = message + iteration.toLittleEndianBytes()
        return hmacSha256(seed, input)
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
        
        // Clear cache first
        geohashIdentityCache.clear()
        
        // Clear Nostr private key using public methods instead of reflection
        try {
            stateManager.clearSecureValues(NOSTR_PRIVATE_KEY, DEVICE_SEED_KEY)
                
            Log.i(TAG, "Cleared all Nostr identity data and cache")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear Nostr data: ${e.message}")
        }
    }
    
    // MARK: - Private Methods
    
    private fun loadNostrPrivateKey(stateManager: SecureIdentityStateManager): String? {
        return try {
            // Use public methods instead of reflection to access the encrypted preferences
            stateManager.getSecureValue(NOSTR_PRIVATE_KEY)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Nostr private key: ${e.message}")
            null
        }
    }
    
    private fun saveNostrPrivateKey(stateManager: SecureIdentityStateManager, privateKeyHex: String) {
        try {
            // Use public methods instead of reflection to access the encrypted preferences
            stateManager.storeSecureValue(NOSTR_PRIVATE_KEY, privateKeyHex)
                
            Log.d(TAG, "Saved Nostr private key to secure storage")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save Nostr private key: ${e.message}")
            throw e
        }
    }
    
    private fun getOrCreateDeviceSeed(stateManager: SecureIdentityStateManager): ByteArray {
        try {
            // Use public methods instead of reflection to access the encrypted preferences
            val existingSeed = stateManager.getSecureValue(DEVICE_SEED_KEY)
            if (existingSeed != null) {
                return android.util.Base64.decode(existingSeed, android.util.Base64.DEFAULT)
            }
            
            // Generate new seed
            val seed = ByteArray(32)
            SecureRandom().nextBytes(seed)
            
            val seedBase64 = android.util.Base64.encodeToString(seed, android.util.Base64.DEFAULT)
            stateManager.storeSecureValue(DEVICE_SEED_KEY, seedBase64)
                
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

// Extension functions for data conversion
private fun String.hexToByteArrayLocal(): ByteArray {
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

private fun ByteArray.toHexStringLocal(): String {
    return joinToString("") { "%02x".format(it) }
}

private fun UInt.toLittleEndianBytes(): ByteArray {
    val bytes = ByteArray(4)
    bytes[0] = (this and 0xFFu).toByte()
    bytes[1] = ((this shr 8) and 0xFFu).toByte()
    bytes[2] = ((this shr 16) and 0xFFu).toByte()
    bytes[3] = ((this shr 24) and 0xFFu).toByte()
    return bytes
}
