package com.bitchat.android.nostr

import kotlinx.serialization.json.*
import com.bitchat.android.util.JsonUtil

/**
 * Nostr protocol request messages
 * Supports EVENT, REQ, and CLOSE message types
 */
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
            val jsonArray = buildJsonArray {
                when (request) {
                    is Event -> {
                        add("EVENT")
                        add(Json.encodeToJsonElement(request.event))
                    }
                    
                    is Subscribe -> {
                        add("REQ")
                        add(request.subscriptionId)
                        request.filters.forEach { filter ->
                            add(filter.toJsonElement())
                        }
                    }
                    
                    is Close -> {
                        add("CLOSE")
                        add(request.subscriptionId)
                    }
                }
            }
            
            return JsonUtil.json.encodeToString(JsonArray.serializer(), jsonArray)
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
                            val event = parseEventFromJson(eventJson)
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
        
        private fun parseEventFromJson(jsonObject: JsonObject): NostrEvent {
            return NostrEvent(
                id = jsonObject["id"]?.jsonPrimitive?.content ?: "",
                pubkey = jsonObject["pubkey"]?.jsonPrimitive?.content ?: "",
                createdAt = jsonObject["created_at"]?.jsonPrimitive?.int ?: 0,
                kind = jsonObject["kind"]?.jsonPrimitive?.int ?: 0,
                tags = parseTagsFromJson(jsonObject["tags"]?.jsonArray),
                content = jsonObject["content"]?.jsonPrimitive?.content ?: "",
                sig = jsonObject["sig"]?.jsonPrimitive?.content
            )
        }
        
        private fun parseTagsFromJson(tagsArray: JsonArray?): List<List<String>> {
            if (tagsArray == null) return emptyList()
            
            return try {
                tagsArray.map { tagElement ->
                    if (tagElement is JsonArray) {
                        tagElement.map { it.jsonPrimitive.content }
                    } else {
                        emptyList()
                    }
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
