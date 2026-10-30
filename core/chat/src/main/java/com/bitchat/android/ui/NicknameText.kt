package com.bitchat.android.ui

/**
 * Split a name into base and a '#abcd' suffix if present (matches iOS splitSuffix exactly)
 */
fun splitSuffix(name: String): Pair<String, String> {
    if (name.length < 5) return Pair(name, "")
    
    val suffix = name.takeLast(5)
    if (suffix.startsWith("#") && suffix.drop(1).all { 
        it.isDigit() || it.lowercaseChar() in 'a'..'f' 
    }) {
        val base = name.dropLast(5)
        return Pair(base, suffix)
    }
    
    return Pair(name, "")
}

/**
 * A bare `anon` label means the geohash heartbeat has not announced a username yet. The transport
 * may append a `#abcd` disambiguator, which does not turn it into an announced name. Names such as
 * `anon1234`, `anonymous`, and `anonracer` are real announced usernames.
 */
fun isUnannouncedNickname(displayName: String): Boolean {
    val base = splitSuffix(displayName.trim()).first
    return base.equals("anon", ignoreCase = true)
}
