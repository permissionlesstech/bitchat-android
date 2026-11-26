package com.bitchat.android.nostr

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import java.security.MessageDigest

/**
 * Nostr Event structure following NIP-01
 * Compatible with iOS implementation
 */
data class NostrEvent(
    var id: String = "",
    val pubkey: String,
    @SerializedName("created_at") val createdAt: Int,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    var sig: String? = null
) {
    
    companion object {
        private val defaultGson = Gson()
        private val compactGson = GsonBuilder().disableHtmlEscaping().create()
        private val sha256ThreadLocal = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

        /**
         * Create from JSON dictionary
         */
        fun fromJson(json: Map<String, Any>): NostrEvent? {
            return try {
                NostrEvent(
                    id = json["id"] as? String ?: "",
                    pubkey = json["pubkey"] as? String ?: return null,
                    createdAt = (json["created_at"] as? Number)?.toInt() ?: return null,
                    kind = (json["kind"] as? Number)?.toInt() ?: return null,
                    tags = (json["tags"] as? List<List<String>>) ?: return null,
                    content = json["content"] as? String ?: return null,
                    sig = json["sig"] as? String?
                )
            } catch (e: Exception) {
                null
            }
        }
        
        /**
         * Create from JSON string
         */
        fun fromJsonString(jsonString: String): NostrEvent? {
            return try {
                defaultGson.fromJson(jsonString, NostrEvent::class.java)
            } catch (e: Exception) {
                null
            }
        }
        
        /**
         * Create a new text note event
         */
        fun createTextNote(
            content: String,
            publicKeyHex: String,
            privateKeyHex: String,
            tags: List<List<String>> = emptyList(),
            createdAt: Int = (System.currentTimeMillis() / 1000).toInt()
        ): NostrEvent {
            val event = NostrEvent(
                pubkey = publicKeyHex,
                createdAt = createdAt,
                kind = NostrKind.TEXT_NOTE,
                tags = tags,
                content = content
            )
            return event.sign(privateKeyHex)
        }
        
        /**
         * Create a new metadata event (kind 0)
         */
        fun createMetadata(
            metadata: String,
            publicKeyHex: String,
            privateKeyHex: String,
            createdAt: Int = (System.currentTimeMillis() / 1000).toInt()
        ): NostrEvent {
            val event = NostrEvent(
                pubkey = publicKeyHex,
                createdAt = createdAt,
                kind = NostrKind.METADATA,
                tags = emptyList(),
                content = metadata
            )
            return event.sign(privateKeyHex)
        }
    }
    
    /**
     * Sign event with secp256k1 private key
     * Returns signed event with id and signature set
     */
    fun sign(privateKeyHex: String): NostrEvent {
        val (eventId, eventIdHash) = calculateEventId()
        
        // Create signature using secp256k1
        val signature = signHash(eventIdHash, privateKeyHex)
        
        return this.copy(
            id = eventId,
            sig = signature
        )
    }
    
    /**
     * Compute event ID (NIP-01) without signing
     */
    fun computeEventIdHex(): String {
        val (eventId, _) = calculateEventId()
        return eventId
    }
    
    /**
     * Calculate event ID according to NIP-01
     * Returns (hex_id, hash_bytes)
     */
    private fun calculateEventId(): Pair<String, ByteArray> {
        // Create serialized array for hashing according to NIP-01
        val serialized = listOf(
            0,
            pubkey,
            createdAt,
            kind,
            tags,
            content
        )
        
        // Convert to JSON without escaping slashes (compact format)
        val jsonString = compactGson.toJson(serialized)
        
        // SHA256 hash of the JSON string
        val digest = sha256ThreadLocal.get()!!
        digest.reset()
        val jsonBytes = jsonString.toByteArray(Charsets.UTF_8)
        val hash = digest.digest(jsonBytes)
        
        // Convert to hex
        val hexId = hash.toHexString()
        
        return Pair(hexId, hash)
    }
    
    /**
     * Sign hash using BIP-340 Schnorr signatures
     */
    private fun signHash(hash: ByteArray, privateKeyHex: String): String {
        return try {
            // Use the real BIP-340 Schnorr signature from NostrCrypto
            NostrCrypto.schnorrSign(hash, privateKeyHex)
        } catch (e: Exception) {
            throw RuntimeException("Failed to sign event: ${e.message}", e)
        }
    }
    
    /**
     * Convert to JSON string
     */
    fun toJsonString(): String {
        return defaultGson.toJson(this)
    }
    
    /**
     * Validate event signature using BIP-340 Schnorr verification
     */
    fun isValidSignature(): Boolean {
        return try {
            val signatureHex = sig ?: return false
            if (id.isEmpty() || pubkey.isEmpty()) return false
            
            // Recalculate the event ID hash for verification
            val (calculatedId, messageHash) = calculateEventId()
            
            // Check if the calculated ID matches the stored ID
            if (calculatedId != id) return false
            
            // Verify the Schnorr signature
            NostrCrypto.schnorrVerify(messageHash, signatureHex, pubkey)
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Validate event structure and signature
     */
    fun isValid(): Boolean {
        return try {
            // Basic field validation
            if (pubkey.isEmpty() || content.isEmpty()) return false
            if (createdAt <= 0 || kind < 0) return false
            if (!NostrCrypto.isValidPublicKey(pubkey)) return false
            
            // Signature validation
            isValidSignature()
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Nostr event kinds
 */
object NostrKind {
    const val METADATA = 0
    const val TEXT_NOTE = 1
    const val DIRECT_MESSAGE = 14     // NIP-17 direct message (unsigned)
    const val FILE_MESSAGE = 15       // NIP-17 file message (unsigned)
    const val SEAL = 13              // NIP-17 sealed event
    const val GIFT_WRAP = 1059       // NIP-17 gift wrap
    const val EPHEMERAL_EVENT = 20000 // For geohash channels
    const val GEOHASH_PRESENCE = 20001 // For geohash presence heartbeat
}

private val NOSTR_HEX_CHARS = charArrayOf(
    '0', '1', '2', '3', '4', '5', '6', '7',
    '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
)

private fun nostrHexNibble(c: Char): Int = when (c) {
    in '0'..'9' -> c.code - '0'.code
    in 'a'..'f' -> c.code - 'a'.code + 10
    in 'A'..'F' -> c.code - 'A'.code + 10
    else -> throw NumberFormatException("Invalid hex char: $c")
}

/**
 * Extension functions for hex encoding/decoding
 */
fun String.hexToByteArray(): ByteArray {
    check(length % 2 == 0) { "Must have an even length" }
    val out = ByteArray(length ushr 1)
    var i = 0
    var pos = 0
    while (pos < length) {
        out[i++] = ((nostrHexNibble(this[pos]) shl 4) or nostrHexNibble(this[pos + 1])).toByte()
        pos += 2
    }
    return out
}

fun ByteArray.toHexString(): String {
    if (isEmpty()) return ""
    val out = CharArray(size * 2)
    var j = 0
    for (b in this) {
        val v = b.toInt() and 0xFF
        out[j++] = NOSTR_HEX_CHARS[v ushr 4]
        out[j++] = NOSTR_HEX_CHARS[v and 0x0F]
    }
    return String(out)
}
