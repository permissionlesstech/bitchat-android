package com.bitchat.android.nostr

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * NIP-17 Protocol Implementation for Private Direct Messages
 * Compatible with iOS implementation
 */
object NostrProtocol {
    
    private const val TAG = "NostrProtocol"
    private val gson = Gson()
    
    /**
     * Create NIP-17 private message gift-wrap (receiver copy only per iOS)
     * Returns a single gift-wrapped event ready for relay broadcast
     */
    fun createPrivateMessage(
        content: String,
        recipientPubkey: String,
        senderIdentity: NostrIdentity
    ): List<NostrEvent> {
        Log.d(TAG, "Creating private message for recipient: ${recipientPubkey.take(16)}...")
        
        // 1. Create the rumor (unsigned kind 14) with p-tag
        val rumorBase = NostrEvent(
            pubkey = senderIdentity.publicKeyHex,
            createdAt = (System.currentTimeMillis() / 1000).toInt(),
            kind = NostrKind.DIRECT_MESSAGE,
            tags = listOf(listOf("p", recipientPubkey)),
            content = content
        )
        val rumorId = rumorBase.computeEventIdHex()
        val rumor = rumorBase.copy(id = rumorId)
        
        // 2. Seal the rumor (kind 13) signed by sender, timestamp randomized up to 2 days
        val sealedEvent = createSeal(
            rumor = rumor,
            recipientPubkey = recipientPubkey,
            senderPrivateKey = senderIdentity.privateKeyHex,
            senderPublicKey = senderIdentity.publicKeyHex
        )
        
        // 3. Gift wrap to recipient (kind 1059)
        val giftWrapToRecipient = createGiftWrap(
            seal = sealedEvent,
            recipientPubkey = recipientPubkey
        )
        Log.d(TAG, "Created gift wrap: toRecipient=${giftWrapToRecipient.id.take(16)}...")
        return listOf(giftWrapToRecipient)
    }
    
    /**
     * Decrypt a received NIP-17 message
     * Returns (content, senderPubkey, timestamp) or null if decryption fails
     */
    fun decryptPrivateMessage(
        giftWrap: NostrEvent,
        recipientIdentity: NostrIdentity
    ): Triple<String, String, Int>? {
        Log.v(TAG, "Starting decryption of gift wrap: ${giftWrap.id.take(16)}...")
        
        return try {
            // 1. Unwrap the gift wrap
            val seal = unwrapGiftWrap(giftWrap, recipientIdentity.privateKeyHex)
                ?: run {
                    Log.w(TAG, "❌ Failed to unwrap gift wrap")
                    return null
                }
            
            Log.v(TAG, "Successfully unwrapped gift wrap from: ${seal.pubkey.take(16)}...")
            
            // 2. Open the seal
            val rumor = openSeal(seal, recipientIdentity.privateKeyHex)
                ?: run {
                    Log.w(TAG, "❌ Failed to open seal")
                    return null
                }
            
            Log.v(TAG, "Successfully opened seal")
            
            Triple(rumor.content, rumor.pubkey, rumor.createdAt)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decrypt private message: ${e.message}")
            null
        }
    }
    
    /**
     * Create a geohash-scoped text note (kind 1) with optional nickname
     * This creates a persistent text note that can be retrieved later
     */
    suspend fun createGeohashTextNote(
        content: String,
        geohash: String,
        senderIdentity: NostrIdentity,
        nickname: String? = null
    ): NostrEvent = withContext(Dispatchers.Default) {
        val tags = mutableListOf<List<String>>()
        tags.add(listOf("g", geohash))
        
        if (!nickname.isNullOrEmpty()) {
            tags.add(listOf("n", nickname))
        }
        
        val event = NostrEvent(
            pubkey = senderIdentity.publicKeyHex,
            createdAt = (System.currentTimeMillis() / 1000).toInt(),
            kind = NostrKind.TEXT_NOTE,
            tags = tags,
            content = content
        )
        
        return@withContext senderIdentity.signEvent(event)
    }
    
    /**
     * Create a geohash-scoped ephemeral public message (kind 20000)
     * Includes Proof of Work mining if enabled in settings
     */
    suspend fun createEphemeralGeohashEvent(
        content: String,
        geohash: String,
        senderIdentity: NostrIdentity,
        nickname: String? = null,
        teleported: Boolean = false
    ): NostrEvent = withContext(Dispatchers.Default) {
        val tags = mutableListOf<List<String>>()
        tags.add(listOf("g", geohash))
        
        if (!nickname.isNullOrEmpty()) {
            tags.add(listOf("n", nickname))
        }
        
        if (teleported) {
            // Use tag consistent with event handlers ("t","teleport")
            tags.add(listOf("t", "teleport"))
        }
        
        var event = NostrEvent(
            pubkey = senderIdentity.publicKeyHex,
            createdAt = (System.currentTimeMillis() / 1000).toInt(),
            kind = NostrKind.EPHEMERAL_EVENT,
            tags = tags,
            content = content
        )
        
        // Check if Proof of Work is enabled
        val powSettings = PoWPreferenceManager.getCurrentSettings()
        if (powSettings.enabled && powSettings.difficulty > 0) {
            Log.d(TAG, "PoW enabled for geohash event: difficulty=${powSettings.difficulty}")
            
            try {
                // Start mining state for animated indicators
                PoWPreferenceManager.startMining()
                
                // Mine the event before signing
                val minedEvent = NostrProofOfWork.mineEvent(
                    event = event,
                    targetDifficulty = powSettings.difficulty,
                    maxIterations = 2_000_000 // Allow up to 2M iterations for reasonable mining time
                )
                
                if (minedEvent != null) {
                    event = minedEvent
                    val actualDifficulty = NostrProofOfWork.calculateDifficulty(event.id)
                    Log.d(TAG, "✅ PoW mining successful: target=${powSettings.difficulty}, actual=$actualDifficulty, nonce=${NostrProofOfWork.getNonce(event)}")
                } else {
                    Log.w(TAG, "❌ PoW mining failed, proceeding without PoW")
                }
            } finally {
                // Always stop mining state when done (success or failure)
                PoWPreferenceManager.stopMining()
            }
        }
        
        return@withContext senderIdentity.signEvent(event)
    }
    
    // MARK: - Private Methods
    
    private fun createSeal(
        rumor: NostrEvent,
        recipientPubkey: String,
        senderPrivateKey: String,
        senderPublicKey: String
    ): NostrEvent {
        val rumorJSON = gson.toJson(rumor)
        
        val encrypted = NostrCrypto.encryptNIP44(
            plaintext = rumorJSON,
            recipientPublicKeyHex = recipientPubkey,
            senderPrivateKeyHex = senderPrivateKey
        )
        
        val seal = NostrEvent(
            pubkey = senderPublicKey,
            createdAt = NostrCrypto.randomizeTimestampUpToPast(),
            kind = NostrKind.SEAL,
            tags = emptyList(),
            content = encrypted
        )
        
        // Sign with the ephemeral key
        return seal.sign(senderPrivateKey)
    }
    
    private fun createGiftWrap(
        seal: NostrEvent,
        recipientPubkey: String
    ): NostrEvent {
        val sealJSON = gson.toJson(seal)
        
        // Create new ephemeral key for gift wrap
        val (wrapPrivateKey, wrapPublicKey) = NostrCrypto.generateKeyPair()
        Log.v(TAG, "Creating gift wrap with ephemeral key")
        
        // Encrypt the seal with the new ephemeral key
        val encrypted = NostrCrypto.encryptNIP44(
            plaintext = sealJSON,
            recipientPublicKeyHex = recipientPubkey,
            senderPrivateKeyHex = wrapPrivateKey
        )
        
        val giftWrap = NostrEvent(
            pubkey = wrapPublicKey,
            createdAt = NostrCrypto.randomizeTimestampUpToPast(),
            kind = NostrKind.GIFT_WRAP,
            tags = listOf(listOf("p", recipientPubkey)), // Tag recipient
            content = encrypted
        )
        
        // Sign with the gift wrap ephemeral key
        return giftWrap.sign(wrapPrivateKey)
    }
    
    private fun unwrapGiftWrap(
        giftWrap: NostrEvent,
        recipientPrivateKey: String
    ): NostrEvent? {
        Log.d(TAG, "Unwrapping gift wrap; content prefix='${giftWrap.content.take(3)}' length=${giftWrap.content.length}")
        
        return try {
            val decrypted = NostrCrypto.decryptNIP44(
                ciphertext = giftWrap.content,
                senderPublicKeyHex = giftWrap.pubkey,
                recipientPrivateKeyHex = recipientPrivateKey
            )
            
            val jsonElement = JsonParser.parseString(decrypted)
            if (!jsonElement.isJsonObject) {
                Log.w(TAG, "Decrypted gift wrap is not a JSON object")
                return null
            }
            
            val jsonObject = jsonElement.asJsonObject
            val seal = NostrEvent(
                id = jsonObject.get("id")?.asString ?: "",
                pubkey = jsonObject.get("pubkey")?.asString ?: "",
                createdAt = jsonObject.get("created_at")?.asInt ?: 0,
                kind = jsonObject.get("kind")?.asInt ?: 0,
                tags = parseTagsFromJson(jsonObject.get("tags")?.asJsonArray) ?: emptyList(),
                content = jsonObject.get("content")?.asString ?: "",
                sig = jsonObject.get("sig")?.asString
            )
            
            Log.v(TAG, "Unwrapped seal with kind: ${seal.kind}")
            seal
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unwrap gift wrap: ${e.message}")
            null
        }
    }
    
    private fun openSeal(
        seal: NostrEvent,
        recipientPrivateKey: String
    ): NostrEvent? {
        return try {
            val decrypted = NostrCrypto.decryptNIP44(
                ciphertext = seal.content,
                senderPublicKeyHex = seal.pubkey,
                recipientPrivateKeyHex = recipientPrivateKey
            )
            
            val jsonElement = JsonParser.parseString(decrypted)
            if (!jsonElement.isJsonObject) {
                Log.w(TAG, "Decrypted seal is not a JSON object")
                return null
            }
            
            val jsonObject = jsonElement.asJsonObject
            NostrEvent(
                id = jsonObject.get("id")?.asString ?: "",
                pubkey = jsonObject.get("pubkey")?.asString ?: "",
                createdAt = jsonObject.get("created_at")?.asInt ?: 0,
                kind = jsonObject.get("kind")?.asInt ?: 0,
                tags = parseTagsFromJson(jsonObject.get("tags")?.asJsonArray) ?: emptyList(),
                content = jsonObject.get("content")?.asString ?: "",
                sig = jsonObject.get("sig")?.asString
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open seal: ${e.message}")
            null
        }
    }
    
    private fun parseTagsFromJson(tagsArray: com.google.gson.JsonArray?): List<List<String>>? {
        if (tagsArray == null) return emptyList()
        
        return try {
            tagsArray.map { tagElement ->
                if (tagElement.isJsonArray) {
                    val tagArray = tagElement.asJsonArray
                    tagArray.map { it.asString }
                } else {
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse tags: ${e.message}")
            null
        }
    }
}
