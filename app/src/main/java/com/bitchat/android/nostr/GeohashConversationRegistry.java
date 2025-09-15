package com.bitchat.android.nostr;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GeohashConversationRegistry
 * - Global, thread-safe registry of conversationKey (e.g., "nostr_<pub16>") -> source geohash
 * - Enables routing geohash DMs from anywhere by providing the correct geohash identity
 */
public class GeohashConversationRegistry {
    private static final Map<String, String> map = new ConcurrentHashMap<>();

    // Private constructor to prevent instantiation
    private GeohashConversationRegistry() {}

    public static void set(String convKey, String geohash) {
        if (geohash != null && !geohash.isEmpty()) {
            map.put(convKey, geohash);
        }
    }

    public static String get(String convKey) {
        return map.get(convKey);
    }

    public static Map<String, String> snapshot() {
        return new HashMap<>(map);
    }

    public static void clear() {
        map.clear();
    }
}
