package com.bitchat.android.ui

import androidx.compose.ui.text.AnnotatedString

/**
 * Utilities for parsing special tokens in chat messages (geohashes, etc.).
 */
object MessageSpecialParser {
    // Standalone geohash pattern like "#9q" or longer. Word boundaries enforced.
    // Geohash alphabet is base32: 0123456789bcdefghjkmnpqrstuvwxyz
    private val standaloneGeohashRegex = Regex("(^|[^A-Za-z0-9_#])#([0-9bcdefghjkmnpqrstuvwxyz]{2,})($|[^A-Za-z0-9_])", RegexOption.IGNORE_CASE)

    data class GeohashMatch(val start: Int, val endExclusive: Int, val geohash: String)

    /**
     * Finds standalone geohashes within [text]. A match is returned only when
     * the '#' token is not part of another word (e.g., not in '@anon#9qk2').
     */
    fun findStandaloneGeohashes(text: String): List<GeohashMatch> {
        if (text.isEmpty()) return emptyList()
        val matches = mutableListOf<GeohashMatch>()
        var index = 0
        while (index < text.length) {
            val m = standaloneGeohashRegex.find(text, index) ?: break
            // Adjust to only cover the geohash token starting at '#'
            val fullRange = m.range
            // Find the '#' within this match substring
            val sub = text.substring(fullRange)
            val hashPos = sub.indexOf('#')
            if (hashPos >= 0) {
                val tokenStart = fullRange.first + hashPos
                // Consume '#' + geohash letters
                var cursor = tokenStart + 1
                while (cursor < text.length) {
                    val ch = text[cursor].lowercaseChar()
                    val isGeoChar = (ch in '0'..'9') || (ch in "bcdefghjkmnpqrstuvwxyz")
                    if (!isGeoChar) break
                    cursor++
                }
                val token = text.substring(tokenStart + 1, cursor)
                if (token.length >= 2) {
                    matches.add(GeohashMatch(tokenStart, cursor, token.lowercase()))
                }
                index = cursor
            } else {
                index = fullRange.last + 1
            }
        }
        return matches
    }
}


