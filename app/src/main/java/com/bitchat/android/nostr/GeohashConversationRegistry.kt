package com.bitchat.android.nostr

import java.util.concurrent.ConcurrentHashMap

/**
 * GeohashConversationRegistry
 * - Global, thread-safe registry of conversationKey (e.g., "nostr_<pub16>") -> source geohash
 * - Enables routing geohash DMs from anywhere by providing the correct geohash identity
 */
object GeohashConversationRegistry {
    private val map = ConcurrentHashMap<String, String>()

    fun set(convKey: String, geohash: String) {
        if (geohash.isNotEmpty()) map[convKey] = geohash
    }

    fun get(convKey: String): String? = map[convKey]

    fun snapshot(): Map<String, String> = map.toMap()

    fun clear() = map.clear()
}
