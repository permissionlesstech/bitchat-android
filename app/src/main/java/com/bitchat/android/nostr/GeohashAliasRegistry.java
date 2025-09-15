package com.bitchat.android.nostr;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GeohashAliasRegistry
 * - Global, thread-safe registry for alias->Nostr pubkey mappings (e.g., nostr_<pub16> -> pubkeyHex)
 * - Allows non-UI components (e.g., MessageRouter) to resolve geohash DM aliases without depending on UI ViewModels
 */
public class GeohashAliasRegistry {
    private static final Map<String, String> map = new ConcurrentHashMap<>();

    // Private constructor to prevent instantiation
    private GeohashAliasRegistry() {}

    public static void put(String alias, String pubkeyHex) {
        map.put(alias, pubkeyHex);
    }

    public static String get(String alias) {
        return map.get(alias);
    }

    public static boolean contains(String alias) {
        return map.containsKey(alias);
    }

    public static Map<String, String> snapshot() {
        return new HashMap<>(map);
    }

    public static void clear() {
        map.clear();
    }
}
