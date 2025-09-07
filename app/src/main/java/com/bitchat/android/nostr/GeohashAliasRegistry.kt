package com.bitchat.android.nostr

import java.util.concurrent.ConcurrentHashMap

/**
 * GeohashAliasRegistry
 * - Global, thread-safe registry for alias->Nostr pubkey mappings (e.g., nostr_<pub16> -> pubkeyHex)
 * - Allows non-UI components (e.g., MessageRouter) to resolve geohash DM aliases without depending on UI ViewModels
 */
object GeohashAliasRegistry {
    private val map: MutableMap<String, String> = ConcurrentHashMap()

    fun put(alias: String, pubkeyHex: String) {
        map[alias] = pubkeyHex
    }

    fun get(alias: String): String? = map[alias]

    fun contains(alias: String): Boolean = map.containsKey(alias)

    fun snapshot(): Map<String, String> = HashMap(map)

    fun clear() { map.clear() }
}
