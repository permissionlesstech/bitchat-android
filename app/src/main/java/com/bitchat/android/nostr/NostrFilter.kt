package com.bitchat.android.nostr

import com.google.gson.*
import com.google.gson.annotations.SerializedName
import java.lang.reflect.Type

/**
 * Nostr event filter for subscriptions
 * Compatible with iOS implementation
 */
data class NostrFilter(
    val ids: List<String>? = null,
    val authors: List<String>? = null,
    val kinds: List<Int>? = null,
    val since: Int? = null,
    val until: Int? = null,
    val limit: Int? = null,
    private val tagFilters: Map<String, List<String>>? = null
) {
    
    companion object {
        /**
         * Create filter for NIP-17 gift wraps
         */
        fun giftWrapsFor(pubkey: String, since: Long? = null): NostrFilter {
            return NostrFilter(
                kinds = listOf(NostrKind.GIFT_WRAP),
                since = since?.let { (it / 1000).toInt() },
                tagFilters = mapOf("p" to listOf(pubkey)),
                limit = 100
            )
        }
        
        /**
         * Create filter for geohash-scoped ephemeral events (kind 20000)
         */
        fun geohashEphemeral(geohash: String, since: Long? = null, limit: Int = 200): NostrFilter {
            return NostrFilter(
                kinds = listOf(NostrKind.EPHEMERAL_EVENT),
                since = since?.let { (it / 1000).toInt() },
                tagFilters = mapOf("g" to listOf(geohash)),
                limit = limit
            )
        }
        
        /**
         * Create filter for text notes from specific authors
         */
        fun textNotesFrom(authors: List<String>, since: Long? = null, limit: Int = 50): NostrFilter {
            return NostrFilter(
                kinds = listOf(NostrKind.TEXT_NOTE),
                authors = authors,
                since = since?.let { (it / 1000).toInt() },
                limit = limit
            )
        }
        
        /**
         * Create filter for geohash-scoped text notes (kind=1 with g tag)
         */
        fun geohashNotes(geohash: String, since: Long? = null, limit: Int = 200): NostrFilter {
            return NostrFilter(
                kinds = listOf(NostrKind.TEXT_NOTE),
                since = since?.let { (it / 1000).toInt() },
                tagFilters = mapOf("g" to listOf(geohash)),
                limit = limit
            )
        }
        
        /**
         * Create filter for specific event IDs
         */
        fun forEvents(ids: List<String>): NostrFilter {
            return NostrFilter(ids = ids)
        }
    }
    
    /**
     * Custom JSON serializer to handle tag filters properly
     */
    class FilterSerializer : JsonSerializer<NostrFilter> {
        override fun serialize(src: NostrFilter, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
            val jsonObject = JsonObject()
            
            // Standard fields
            src.ids?.let { jsonObject.add("ids", context.serialize(it)) }
            src.authors?.let { jsonObject.add("authors", context.serialize(it)) }
            src.kinds?.let { jsonObject.add("kinds", context.serialize(it)) }
            src.since?.let { jsonObject.addProperty("since", it) }
            src.until?.let { jsonObject.addProperty("until", it) }
            src.limit?.let { jsonObject.addProperty("limit", it) }
            
            // Tag filters with # prefix
            src.tagFilters?.forEach { (tag, values) ->
                jsonObject.add("#$tag", context.serialize(values))
            }
            
            return jsonObject
        }
    }
    
    /**
     * Create builder for complex filters
     */
    class Builder {
        private var ids: List<String>? = null
        private var authors: List<String>? = null
        private var kinds: List<Int>? = null
        private var since: Int? = null
        private var until: Int? = null
        private var limit: Int? = null
        private val tagFilters = mutableMapOf<String, List<String>>()
        
        fun ids(vararg ids: String) = apply { this.ids = ids.toList() }
        fun authors(vararg authors: String) = apply { this.authors = authors.toList() }
        fun kinds(vararg kinds: Int) = apply { this.kinds = kinds.toList() }
        fun since(timestamp: Long) = apply { this.since = (timestamp / 1000).toInt() }
        fun until(timestamp: Long) = apply { this.until = (timestamp / 1000).toInt() }
        fun limit(count: Int) = apply { this.limit = count }
        
        fun tagP(vararg pubkeys: String) = apply { tagFilters["p"] = pubkeys.toList() }
        fun tagE(vararg eventIds: String) = apply { tagFilters["e"] = eventIds.toList() }
        fun tagG(vararg geohashes: String) = apply { tagFilters["g"] = geohashes.toList() }
        fun tag(name: String, vararg values: String) = apply { tagFilters[name] = values.toList() }
        
        fun build(): NostrFilter {
            return NostrFilter(
                ids = ids,
                authors = authors,
                kinds = kinds,
                since = since,
                until = until,
                limit = limit,
                tagFilters = tagFilters.toMap()
            )
        }
    }
    
    /**
     * Check if this filter matches an event
     */
    fun matches(event: NostrEvent): Boolean {
        // Check IDs
        if (ids != null && !ids.contains(event.id)) {
            return false
        }
        
        // Check authors
        if (authors != null && !authors.contains(event.pubkey)) {
            return false
        }
        
        // Check kinds
        if (kinds != null && !kinds.contains(event.kind)) {
            return false
        }
        
        // Check time bounds
        if (since != null && event.createdAt < since) {
            return false
        }
        
        if (until != null && event.createdAt > until) {
            return false
        }
        
        // Check tag filters
        if (tagFilters != null) {
            for ((tagName, requiredValues) in tagFilters) {
                val eventTags = event.tags.filter { it.isNotEmpty() && it[0] == tagName }
                val eventValues = eventTags.mapNotNull { tag ->
                    if (tag.size > 1) tag[1] else null
                }
                
                val hasMatch = requiredValues.any { requiredValue ->
                    eventValues.contains(requiredValue)
                }
                
                if (!hasMatch) {
                    return false
                }
            }
        }
        
        return true
    }
    
    /**
     * Get debug description
     */
    fun getDebugDescription(): String {
        val parts = mutableListOf<String>()
        
        ids?.let { parts.add("ids=${it.size}") }
        authors?.let { parts.add("authors=${it.size}") }
        kinds?.let { parts.add("kinds=$it") }
        since?.let { parts.add("since=$it") }
        until?.let { parts.add("until=$it") }
        limit?.let { parts.add("limit=$it") }
        tagFilters?.let { filters ->
            filters.forEach { (tag, values) ->
                parts.add("#$tag=${values.size}")
            }
        }
        
        return "NostrFilter(${parts.joinToString(", ")})"
    }
    
    /**
     * Get geohash value from g tag filter (if present)
     * Returns the first geohash in the filter or null if none
     */
    fun getGeohash(): String? {
        return tagFilters?.get("g")?.firstOrNull()
    }
}
