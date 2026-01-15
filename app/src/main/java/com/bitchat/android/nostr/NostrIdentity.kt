package com.bitchat.android.nostr

import android.content.Context
import android.util.Log
import com.bitchat.android.identity.SecureIdentityStateManager
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    /**
     * Get the nsec (bech32-encoded private key) for backup/export
     */
    fun getNsec(): String {
        return Bech32.encode("nsec", privateKeyHex.hexToByteArrayLocal())
    }

    /**
     * Get short display format for nsec
     */
    fun getShortNsec(): String {
        val nsec = getNsec()
        return if (nsec.length > 16) {
            "${nsec.take(8)}...${nsec.takeLast(8)}"
        } else {
            nsec
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
    
    // Cache for resolved profile names (pubkeyHex -> displayName)
    private val profileNameCache = mutableMapOf<String, String>()
    
    // Pending profile resolutions to avoid duplicate requests
    private val pendingResolutions = mutableSetOf<String>()
    
    // Listeners for profile name updates (messageId -> callback)
    private val profileUpdateListeners = mutableMapOf<String, () -> Unit>()
    
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

    /**
     * Import a Nostr identity from an nsec (bech32-encoded private key)
     * Returns the imported identity on success, or null on failure
     */
    fun importFromNsec(nsec: String, context: Context): NostrIdentity? {
        return try {
            val trimmed = nsec.trim().lowercase()
            val (hrp, data) = Bech32.decode(trimmed)
            
            require(hrp == "nsec") { "Invalid nsec prefix, got: $hrp" }
            
            val privateKeyHex = data.toHexStringLocal()
            require(NostrCrypto.isValidPrivateKey(privateKeyHex)) { "Invalid private key" }
            
            val identity = NostrIdentity.fromPrivateKey(privateKeyHex)
            
            // Save to secure storage
            val stateManager = SecureIdentityStateManager(context)
            saveNostrPrivateKey(stateManager, privateKeyHex)
            
            // Clear derived identity cache since main identity changed
            geohashIdentityCache.clear()
            
            Log.i(TAG, "Successfully imported Nostr identity: ${identity.getShortNpub()}")
            identity
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import nsec: ${e.message}")
            null
        }
    }

    /**
     * Export the current identity's nsec for backup
     * Returns null if no identity exists
     */
    fun exportNsec(context: Context): String? {
        return try {
            val identity = getCurrentNostrIdentity(context)
            identity?.getNsec()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export nsec: ${e.message}")
            null
        }
    }

    /**
     * Check if an nsec string is valid (for UI validation)
     */
    fun isValidNsec(nsec: String): Boolean {
        return try {
            val trimmed = nsec.trim().lowercase()
            val (hrp, data) = Bech32.decode(trimmed)
            hrp == "nsec" && NostrCrypto.isValidPrivateKey(data.toHexStringLocal())
        } catch (e: Exception) {
            false
        }
    }

    // -- Public-only NPUB storage and helpers --
    private const val NOSTR_PUBLIC_ONLY = "nostr_public_only"

    fun setPublicOnlyNpub(context: Context, npub: String?) {
        try {
            val stateManager = SecureIdentityStateManager(context)
            if (npub == null) {
                stateManager.clearSecureValues(NOSTR_PUBLIC_ONLY)
                Log.d(TAG, "Cleared public-only npub")
            } else {
                stateManager.storeSecureValue(NOSTR_PUBLIC_ONLY, npub)
                Log.d(TAG, "Stored public-only npub: ${npub.take(16)}...")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set public-only npub: ${e.message}")
        }
    }

    fun getPublicOnlyNpub(context: Context): String? {
        return try {
            val stateManager = SecureIdentityStateManager(context)
            stateManager.getSecureValue(NOSTR_PUBLIC_ONLY)
        } catch (e: Exception) {
            null
        }
    }

    fun isValidNpub(npub: String): Boolean {
        return try {
            val trimmed = npub.trim()
            val (hrp, data) = Bech32.decode(trimmed)
            hrp == "npub" && data.size == 32
        } catch (e: Exception) {
            false
        }
    }

    // Note: pub-derivation helpers are provided on the bridge for reuse across UI/logic.

    /**
     * Return pubkey hex derived from an npub, or null if invalid
     */
    fun getPubHexFromNpub(npub: String): String? {
        return try {
            val trimmed = npub.trim()
            val (hrp, data) = Bech32.decode(trimmed)
            if (hrp != "npub" || data.size != 32) return null
            data.toHexStringLocal()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Return the effective public key hex to use for subscriptions/presence.
     * Priority: private key -> public-only npub -> null
     */
    fun getEffectivePublicKeyHex(context: Context): String? {
        return try {
            val stateManager = SecureIdentityStateManager(context)
            val private = stateManager.getSecureValue(NOSTR_PRIVATE_KEY)
            if (!private.isNullOrEmpty()) {
                return NostrCrypto.derivePublicKey(private)
            }

            val pubNpub = stateManager.getSecureValue(NOSTR_PUBLIC_ONLY)
            if (!pubNpub.isNullOrEmpty()) {
                val (_, data) = Bech32.decode(pubNpub.trim())
                return data.toHexStringLocal()
            }

            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get effective public key: ${e.message}")
            null
        }
    }

    /**
     * Remove stored private key while preserving public-only npub if present
     */
    fun clearPrivateKey(context: Context) {
        try {
            val stateManager = SecureIdentityStateManager(context)
            stateManager.clearSecureValues(NOSTR_PRIVATE_KEY)
            // Also clear any cached identities derived from private key
            geohashIdentityCache.clear()
            Log.i(TAG, "Cleared stored Nostr private key")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear private key: ${e.message}")
        }
    }

    /**
     * Parse Nostr content and replace nostr: URIs with human-readable text.
     * Uses cached names if available, otherwise shows truncated version.
     * Call resolveNostrReferences() to fetch names asynchronously.
     */
    fun parseNostrContent(content: String): String {
        // Pattern to match nostr: URIs (bech32 can include digits and lowercase letters)
        val nostrUriPattern = "nostr:([a-z]+1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]+)".toRegex(RegexOption.IGNORE_CASE)
        
        return nostrUriPattern.replace(content) { matchResult ->
            val bech32 = matchResult.groupValues[1].lowercase()
            try {
                when {
                    bech32.startsWith("npub1") -> {
                        // Check cache first
                        val pubkeyHex = try {
                            val (_, data) = Bech32.decode(bech32)
                            data.toHexStringLocal()
                        } catch (e: Exception) { null }
                        
                        val cachedName = pubkeyHex?.let { profileNameCache[it] }
                        if (cachedName != null) {
                            "@$cachedName"
                        } else {
                            "@${bech32.take(10)}…${bech32.takeLast(4)}"
                        }
                    }
                    bech32.startsWith("note1") -> {
                        "📝${bech32.take(9)}…"
                    }
                    bech32.startsWith("nevent1") -> {
                        "🔗event…"
                    }
                    bech32.startsWith("nprofile1") -> {
                        // Try to extract pubkey from TLV and check cache
                        val pubkeyHex = extractPubkeyFromNprofile(bech32)
                        val cachedName = pubkeyHex?.let { profileNameCache[it] }
                        if (cachedName != null) {
                            "👤@$cachedName"
                        } else {
                            "👤profile…"
                        }
                    }
                    bech32.startsWith("naddr1") -> {
                        "📄addr…"
                    }
                    bech32.startsWith("nrelay1") -> {
                        "🌐relay…"
                    }
                    else -> {
                        "nostr:${bech32.take(12)}…"
                    }
                }
            } catch (e: Exception) {
                "nostr:${bech32.take(12)}…"
            }
        }
    }

    /**
     * Extract all npub/nprofile references from content and resolve them asynchronously.
     * When resolved, calls onUpdate so the UI can refresh.
     */
    fun resolveNostrReferences(content: String, onUpdate: () -> Unit) {
        val nostrUriPattern = "nostr:([a-z]+1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]+)".toRegex(RegexOption.IGNORE_CASE)
        val matches = nostrUriPattern.findAll(content)
        
        val pubkeysToResolve = mutableListOf<String>()
        
        for (match in matches) {
            val bech32 = match.groupValues[1].lowercase()
            try {
                val pubkeyHex: String? = when {
                    bech32.startsWith("npub1") -> {
                        val (_, data) = Bech32.decode(bech32)
                        data.toHexStringLocal()
                    }
                    bech32.startsWith("nprofile1") -> {
                        extractPubkeyFromNprofile(bech32)
                    }
                    else -> null
                }
                
                if (pubkeyHex != null && 
                    !profileNameCache.containsKey(pubkeyHex) && 
                    !pendingResolutions.contains(pubkeyHex)) {
                    pubkeysToResolve.add(pubkeyHex)
                    pendingResolutions.add(pubkeyHex)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to extract pubkey from $bech32: ${e.message}")
            }
        }
        
        // Resolve each pubkey asynchronously
        for (pubkeyHex in pubkeysToResolve) {
            fetchProfileName(pubkeyHex) { name ->
                if (name != null) {
                    profileNameCache[pubkeyHex] = name
                    onUpdate()
                }
                pendingResolutions.remove(pubkeyHex)
            }
        }
    }

    /**
     * Fetch profile name for a pubkey from relays
     */
    private fun fetchProfileName(pubkeyHex: String, onResult: (String?) -> Unit) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val relayManager = NostrRelayManager.shared
                val filter = NostrFilter.profileMetadata(pubkeyHex)
                val subscriptionId = "name-${pubkeyHex.take(8)}-${System.currentTimeMillis()}"
                
                var resolved = false
                
                relayManager.subscribe(
                    filter = filter,
                    id = subscriptionId,
                    handler = { event ->
                        if (!resolved && event.kind == NostrKind.METADATA && event.pubkey == pubkeyHex) {
                            resolved = true
                            try {
                                val profileJson = com.google.gson.JsonParser.parseString(event.content).asJsonObject
                                val name = profileJson.get("name")?.asString?.takeIf { it.isNotBlank() }
                                    ?: profileJson.get("display_name")?.asString?.takeIf { it.isNotBlank() }
                                
                                relayManager.unsubscribe(subscriptionId)
                                
                                GlobalScope.launch(Dispatchers.Main) {
                                    onResult(name)
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to parse profile for $pubkeyHex: ${e.message}")
                            }
                        }
                    }
                )
                
                // Timeout after 5 seconds
                delay(5000)
                if (!resolved) {
                    relayManager.unsubscribe(subscriptionId)
                    onResult(null)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch profile name: ${e.message}")
                onResult(null)
            }
        }
    }

    /**
     * Try to extract pubkey hex from nprofile1 TLV encoding.
     * nprofile uses: 0x00 = pubkey (32 bytes), 0x01 = relay
     */
    private fun extractPubkeyFromNprofile(nprofile: String): String? {
        return try {
            val (_, data) = Bech32.decode(nprofile)
            // TLV: first byte is type (0x00 for pubkey), second is length
            if (data.size >= 34 && data[0] == 0.toByte() && data[1] == 32.toByte()) {
                data.sliceArray(2..33).toHexStringLocal()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get cached profile name for a pubkey, if available
     */
    fun getCachedProfileName(pubkeyHex: String): String? {
        return profileNameCache[pubkeyHex]
    }

    // Popular relays known for good profile/metadata coverage
    private val PROFILE_RELAYS = listOf(
        "wss://relay.damus.io",
        "wss://relay.primal.net",
        "wss://relay.nostr.band",
        "wss://nos.lol",
        "wss://relay.snort.social",
        "wss://purplepag.es",
        "wss://nostr.wine",
        "wss://relay.nostr.info"
    )

    /**
     * Fetch profile metadata (kind 0) for a pubkey from Nostr relays.
     * Returns a callback when profile is found with name, displayName, about, picture, nip05.
     * This is an async operation - the callback may be called after some delay or not at all if no profile is found.
     */
    fun fetchProfileFromRelays(
        pubkeyHex: String,
        onProfileFetched: (name: String?, displayName: String?, about: String?, picture: String?, nip05: String?) -> Unit
    ) {
        try {
            val relayManager = NostrRelayManager.shared
            val filter = NostrFilter.profileMetadata(pubkeyHex)
            val subscriptionId = "profile-${pubkeyHex.take(8)}-${System.currentTimeMillis()}"
            
            Log.d(TAG, "Fetching profile for pubkey: ${pubkeyHex.take(16)}... using ${PROFILE_RELAYS.size} profile relays")
            
            // First ensure we're connected to profile-focused relays
            relayManager.connectToAdditionalRelays(PROFILE_RELAYS)
            
            // Small delay to allow connections to be established, then subscribe
            GlobalScope.launch(Dispatchers.IO) {
                delay(500) // Give relays time to connect
                
                relayManager.subscribe(
                    filter = filter,
                    id = subscriptionId,
                    targetRelayUrls = PROFILE_RELAYS,
                    handler = { event ->
                        if (event.kind == NostrKind.METADATA && event.pubkey == pubkeyHex) {
                            try {
                                // Parse the content as JSON to extract profile fields
                                val profileJson = com.google.gson.JsonParser.parseString(event.content).asJsonObject
                                
                                val name = profileJson.get("name")?.asString
                                val displayName = profileJson.get("display_name")?.asString
                                val about = profileJson.get("about")?.asString
                                val picture = profileJson.get("picture")?.asString
                                val nip05 = profileJson.get("nip05")?.asString
                                
                                Log.i(TAG, "Found profile for ${pubkeyHex.take(16)}: name=$name, displayName=$displayName")
                                
                                // Unsubscribe after receiving profile
                                relayManager.unsubscribe(subscriptionId)
                                
                                // Call the callback with profile data
                                onProfileFetched(name, displayName, about, picture, nip05)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to parse profile content: ${e.message}")
                            }
                        }
                    }
                )
                
                // Set a timeout to unsubscribe if no profile is found (10 seconds)
                delay(10000)
                relayManager.unsubscribe(subscriptionId)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch profile: ${e.message}")
        }
    }

    // Note: pub-derivation helpers are provided on the bridge for reuse across UI/logic.

    
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
