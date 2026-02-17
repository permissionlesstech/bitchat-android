package com.bitchat.android.nostr

import com.bitchat.android.serialization.JsonConfig
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

/**
 * Nostr Event structure following NIP-01
 * Compatible with iOS implementation
 */
@Serializable
data class NostrEvent(
    var id: String = "",
    val pubkey: String,
    @SerialName("created_at") val createdAt: Int,
    val kind: Int,
    @Serializable(with = TagListSerializer::class)
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
        
        /**
         * Create from JSON string
         */
        fun fromJsonString(jsonString: String): NostrEvent? {
            return try {
                JsonConfig.json.decodeFromString(serializer(), jsonString)
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
        // Convert to JSON without escaping slashes (compact format)
        val jsonArray = buildJsonArray {
            add(JsonPrimitive(0))
            add(JsonPrimitive(pubkey))
            add(JsonPrimitive(createdAt))
            add(JsonPrimitive(kind))
            add(
                JsonArray(
                    tags.map { tag ->
                        JsonArray(tag.map { JsonPrimitive(it) })
                    }
                )
            )
            add(JsonPrimitive(content))
        }
        val jsonString = JsonConfig.json.encodeToString(JsonArray.serializer(), jsonArray)
        
        // SHA256 hash of the JSON string
        val digest = MessageDigest.getInstance("SHA-256")
        val jsonBytes = jsonString.toByteArray(Charsets.UTF_8)
        val hash = digest.digest(jsonBytes)
        
        // Convert to hex
        val hexId = hash.joinToString("") { "%02x".format(it) }
        
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
        return JsonConfig.json.encodeToString(serializer(), this)
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

private object TagListSerializer : KSerializer<List<List<String>>> {
    @OptIn(InternalSerializationApi::class)
    override val descriptor: SerialDescriptor =
        buildSerialDescriptor("TagList", StructureKind.LIST)

    override fun serialize(encoder: Encoder, value: List<List<String>>) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("TagListSerializer only supports JSON")
        val jsonArray = JsonArray(
            value.map { tag ->
                JsonArray(tag.map { JsonPrimitive(it) })
            }
        )
        jsonEncoder.encodeJsonElement(jsonArray)
    }

    override fun deserialize(decoder: Decoder): List<List<String>> {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("TagListSerializer only supports JSON")
        val element = jsonDecoder.decodeJsonElement()
        val jsonArray = element as? JsonArray
            ?: throw SerializationException("Expected JsonArray for tags")

        return jsonArray.mapNotNull { tagElement ->
            val tagArray = tagElement as? JsonArray ?: return@mapNotNull null
            tagArray.map { item ->
                item.jsonPrimitive.content
            }
        }
    }
}
