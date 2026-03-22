package com.bitchat.android.nostr

import com.bitchat.android.serialization.JsonConfig
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Nostr protocol request messages
 * Supports EVENT, REQ, and CLOSE message types
 */
@Serializable(with = NostrRequestSerializer::class)
sealed class NostrRequest {
    
    /**
     * EVENT message - publish an event
     */
    data class Event(val event: NostrEvent) : NostrRequest()
    
    /**
     * REQ message - subscribe to events
     */
    data class Subscribe(
        val subscriptionId: String,
        val filters: List<NostrFilter>
    ) : NostrRequest()
    
    /**
     * CLOSE message - close a subscription
     */
    data class Close(val subscriptionId: String) : NostrRequest()
    
    companion object {
        /**
         * Serialize request to JSON string
         */
        fun toJson(request: NostrRequest): String {
            return JsonConfig.json.encodeToString(NostrRequestSerializer, request)
        }
    }
}

/**
 * Custom JSON serializer for NostrRequest
 */
@OptIn(InternalSerializationApi::class)
object NostrRequestSerializer : KSerializer<NostrRequest> {
    override val descriptor: SerialDescriptor =
        buildSerialDescriptor("NostrRequest", StructureKind.LIST)

    override fun serialize(encoder: Encoder, value: NostrRequest) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("NostrRequestSerializer only supports JSON")

        val jsonArray = buildJsonArray {
            when (value) {
                is NostrRequest.Event -> {
                    add(JsonPrimitive("EVENT"))
                    add(JsonConfig.json.encodeToJsonElement(NostrEvent.serializer(), value.event))
                }
                is NostrRequest.Subscribe -> {
                    add(JsonPrimitive("REQ"))
                    add(JsonPrimitive(value.subscriptionId))
                    value.filters.forEach { filter ->
                        add(JsonConfig.json.encodeToJsonElement(NostrFilter.NostrFilterSerializer, filter))
                    }
                }
                is NostrRequest.Close -> {
                    add(JsonPrimitive("CLOSE"))
                    add(JsonPrimitive(value.subscriptionId))
                }
            }
        }

        jsonEncoder.encodeJsonElement(jsonArray)
    }

    override fun deserialize(decoder: Decoder): NostrRequest {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("NostrRequestSerializer only supports JSON")
        val element = jsonDecoder.decodeJsonElement()
        val jsonArray = element as? JsonArray
            ?: throw SerializationException("Expected JsonArray for NostrRequest")
        if (jsonArray.isEmpty()) {
            throw SerializationException("Empty NostrRequest array")
        }

        val type = jsonArray[0].jsonPrimitive.content
        return when (type) {
            "EVENT" -> {
                val eventElement = jsonArray.getOrNull(1) as? JsonObject
                    ?: throw SerializationException("Missing event payload")
                val event = jsonDecoder.json.decodeFromJsonElement(NostrEvent.serializer(), eventElement)
                NostrRequest.Event(event)
            }
            "REQ" -> {
                val subscriptionId = jsonArray.getOrNull(1)?.jsonPrimitive?.content
                    ?: throw SerializationException("Missing subscriptionId")
                val filters = jsonArray.drop(2).map { filterElement ->
                    jsonDecoder.json.decodeFromJsonElement(NostrFilter.NostrFilterSerializer, filterElement)
                }
                NostrRequest.Subscribe(subscriptionId, filters)
            }
            "CLOSE" -> {
                val subscriptionId = jsonArray.getOrNull(1)?.jsonPrimitive?.content
                    ?: throw SerializationException("Missing subscriptionId")
                NostrRequest.Close(subscriptionId)
            }
            else -> throw SerializationException("Unknown NostrRequest type: $type")
        }
    }
}

/**
 * Nostr protocol response messages
 * Handles EVENT, EOSE, OK, and NOTICE responses
 */
sealed class NostrResponse {
    
    /**
     * EVENT response - received event from subscription
     */
    data class Event(
        val subscriptionId: String,
        val event: NostrEvent
    ) : NostrResponse()
    
    /**
     * EOSE response - end of stored events
     */
    data class EndOfStoredEvents(
        val subscriptionId: String
    ) : NostrResponse()
    
    /**
     * OK response - event publication result
     */
    data class Ok(
        val eventId: String,
        val accepted: Boolean,
        val message: String?
    ) : NostrResponse()
    
    /**
     * NOTICE response - relay notice
     */
    data class Notice(
        val message: String
    ) : NostrResponse()
    
    /**
     * Unknown response type
     */
    data class Unknown(
        val raw: String
    ) : NostrResponse()
    
    companion object {
        /**
         * Parse JSON array response
         */
        fun fromJsonArray(jsonArray: JsonArray): NostrResponse {
            return try {
                when (val type = jsonArray[0].jsonPrimitive.content) {
                    "EVENT" -> {
                        if (jsonArray.size >= 3) {
                            val subscriptionId = jsonArray[1].jsonPrimitive.content
                            val eventJson = jsonArray[2].jsonObject
                            val event = JsonConfig.json.decodeFromJsonElement(NostrEvent.serializer(), eventJson)
                            Event(subscriptionId, event)
                        } else {
                            Unknown(jsonArray.toString())
                        }
                    }
                    
                    "EOSE" -> {
                        if (jsonArray.size >= 2) {
                            val subscriptionId = jsonArray[1].jsonPrimitive.content
                            EndOfStoredEvents(subscriptionId)
                        } else {
                            Unknown(jsonArray.toString())
                        }
                    }
                    
                    "OK" -> {
                        if (jsonArray.size >= 3) {
                            val eventId = jsonArray[1].jsonPrimitive.content
                            val accepted = jsonArray[2].jsonPrimitive.boolean
                            val message = if (jsonArray.size >= 4) {
                                jsonArray[3].jsonPrimitive.content
                            } else null
                            Ok(eventId, accepted, message)
                        } else {
                            Unknown(jsonArray.toString())
                        }
                    }
                    
                    "NOTICE" -> {
                        if (jsonArray.size >= 2) {
                            val message = jsonArray[1].jsonPrimitive.content
                            Notice(message)
                        } else {
                            Unknown(jsonArray.toString())
                        }
                    }
                    
                    else -> Unknown(jsonArray.toString())
                }
            } catch (e: Exception) {
                Unknown(jsonArray.toString())
            }
        }
    }
}
