package com.bitchat.android.nostr

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger
import java.util.*

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
     * Calculate event ID according to NIP-01
     * Returns (hex_id, hash_bytes)
     */
    private fun calculateEventId(): Pair<String, ByteArray> {
        // Create serialized array for hashing
        val serialized = listOf(
            0,
            pubkey,
            createdAt,
            kind,
            tags,
            content
        )
        
        // Convert to JSON without escaping slashes
        val gson = Gson()
        val jsonString = gson.toJson(serialized)
        
        // SHA256 hash
        val digest = SHA256Digest()
        val jsonBytes = jsonString.toByteArray(Charsets.UTF_8)
        digest.update(jsonBytes, 0, jsonBytes.size)
        val hash = ByteArray(digest.digestSize)
        digest.doFinal(hash, 0)
        
        // Convert to hex
        val hexId = hash.joinToString("") { "%02x".format(it) }
        
        return Pair(hexId, hash)
    }
    
    /**
     * Sign hash using secp256k1 Schnorr signatures (BIP 340)
     * This is a simplified implementation - in production you'd use a proper Schnorr library
     */
    private fun signHash(hash: ByteArray, privateKeyHex: String): String {
        try {
            // For now, use ECDSA as a placeholder until we implement proper Schnorr
            // TODO: Replace with proper BIP-340 Schnorr signatures
            
            val privateKeyBytes = privateKeyHex.hexToByteArray()
            val privateKeyBigInt = BigInteger(1, privateKeyBytes)
            
            // Create ECDSA signer (this is temporary)
            val signer = ECDSASigner()
            val privateKeyParams = ECPrivateKeyParameters(privateKeyBigInt, NostrCrypto.secp256k1Params)
            signer.init(true, privateKeyParams)
            
            // Sign the hash
            val signature = signer.generateSignature(hash)
            val r = signature[0]
            val s = signature[1]
            
            // Encode as hex (this is not correct Schnorr format, just for initial testing)
            val rBytes = r.toByteArray().takeLast(32).toByteArray()
            val sBytes = s.toByteArray().takeLast(32).toByteArray()
            
            // Pad to 32 bytes if needed
            val rPadded = ByteArray(32)
            val sPadded = ByteArray(32)
            System.arraycopy(rBytes, 0, rPadded, 32 - rBytes.size, rBytes.size)
            System.arraycopy(sBytes, 0, sPadded, 32 - sBytes.size, sBytes.size)
            
            return (rPadded + sPadded).toHexString()
        } catch (e: Exception) {
            throw RuntimeException("Failed to sign event: ${e.message}", e)
        }
    }
    
    /**
     * Convert to JSON string
     */
    fun toJsonString(): String {
        val gson = Gson()
        return gson.toJson(this)
    }
    
    /**
     * Validate event signature
     */
    fun isValidSignature(): Boolean {
        // TODO: Implement signature verification
        return sig != null && id.isNotEmpty()
    }
}

/**
 * Nostr event kinds
 */
object NostrKind {
    const val METADATA = 0
    const val TEXT_NOTE = 1
    const val SEAL = 13              // NIP-17 sealed event
    const val GIFT_WRAP = 1059       // NIP-17 gift wrap
    const val EPHEMERAL_EVENT = 20000 // For geohash channels
}

/**
 * Extension functions for hex encoding/decoding
 */
fun String.hexToByteArray(): ByteArray {
    check(length % 2 == 0) { "Must have an even length" }
    return chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()
}

fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
