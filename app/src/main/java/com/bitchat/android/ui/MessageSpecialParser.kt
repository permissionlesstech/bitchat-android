package com.bitchat.android.ui

import androidx.compose.ui.text.AnnotatedString
import android.util.Patterns

/**
 * Utilities for parsing special tokens in chat messages (geohashes, etc.).
 */
object MessageSpecialParser {
    // Standalone geohash pattern like "#9q" or longer. Word boundaries enforced.
    // Geohash alphabet is base32: 0123456789bcdefghjkmnpqrstuvwxyz
    private val standaloneGeohashRegex = Regex("(^|[^A-Za-z0-9_#])#([0-9bcdefghjkmnpqrstuvwxyz]{2,})($|[^A-Za-z0-9_])", RegexOption.IGNORE_CASE)

    data class GeohashMatch(val start: Int, val endExclusive: Int, val geohash: String)
    data class UrlMatch(val start: Int, val endExclusive: Int, val url: String)
    data class CashuTokenMatch(val start: Int, val endExclusive: Int, val token: String)

    // Cashu bearer tokens (cashuA…/cashuB…). The negative lookbehind keeps the
    // match off words that merely contain "cashuA" (e.g. "xcashuA…"), and the
    // token embedded in a cashu:/cashu:// URI matches naturally after the ':'
    // or '//'. Charset mirrors CashuTokenDecoder.bareToken ('.' appears in
    // some legacy multi-part tokens).
    private val cashuTokenRegex = Regex("(?<![A-Za-z0-9])(cashu[AB][A-Za-z0-9\\-_+/=.]{6,})")

    /**
     * Finds up to [max] distinct Cashu tokens in [text], as the bare bearer
     * strings. Repeated tokens are one bearer instrument — one chip is enough.
     */
    fun findCashuTokens(text: String, max: Int = 3): List<CashuTokenMatch> {
        if (text.isEmpty()) return emptyList()
        val results = mutableListOf<CashuTokenMatch>()
        val seen = mutableSetOf<String>()
        for (m in cashuTokenRegex.findAll(text)) {
            var token = m.groupValues[1]
            var endExclusive = m.range.last + 1
            // '.' is a legacy multipart separator *inside* tokens, but a
            // trailing run of punctuation is prose glued to the token
            // ("redeem cashuAxyz.") — strip it so the chip redeems the
            // real bearer string.
            while (token.isNotEmpty() && token.last() in ".,;:!?") {
                token = token.dropLast(1)
                endExclusive--
            }
            if (token.length <= 6) continue // "cashuA"/"cashuB" with no payload
            if (token.length > com.bitchat.android.cashu.CashuTokenDecoder.MAX_TOKEN_LENGTH) continue
            if (!seen.add(token)) continue
            results.add(CashuTokenMatch(m.range.first, endExclusive, token))
            if (results.size >= max) break
        }
        return results
    }

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

    /**
     * Detect URLs in text. Supports http(s) schemes, www.* domains, and bare domains with TLDs.
     * Trailing punctuation like '.', ',', ')', '!' is trimmed from the match.
     */
    fun findUrls(text: String): List<UrlMatch> {
        if (text.isEmpty()) return emptyList()
        val results = mutableListOf<UrlMatch>()

        // 1) Use Android's WEB_URL for robust detection of http(s) and www.*
        val webUrl = Patterns.WEB_URL
        val matcher = webUrl.matcher(text)
        while (matcher.find()) {
            var start = matcher.start()
            var endExclusive = matcher.end()
            var token = text.substring(start, endExclusive)

            // Trim trailing punctuation
            while (token.isNotEmpty() && token.last() in setOf('.', ',', ';', ':', '!', '?', '\'', '"')) {
                endExclusive -= 1
                token = text.substring(start, endExclusive)
            }
            results.add(UrlMatch(start, endExclusive, token))
        }

        // 2) Bare-domain fallback for things WEB_URL can miss (e.g., google.com)
        val bare = Regex("(?<!@)(?<![A-Za-z0-9_-])([A-Za-z0-9-]+\\.[A-Za-z]{2,}(?:/[A-Za-z0-9@:%._+~#=/?&!$'()*,-]*)?)")
        for (m in bare.findAll(text)) {
            val start = m.range.first
            var endExclusive = m.range.last + 1
            var token = text.substring(start, endExclusive)

            // Exclude if overlaps any already-found match
            val overlapsExisting = results.any { start < it.endExclusive && endExclusive > it.start }
            if (overlapsExisting) continue

            // Skip emails
            if (token.contains('@')) continue

            // Trim trailing punctuation
            while (token.isNotEmpty() && token.last() in setOf('.', ',', ';', ':', '!', '?', '\'', '"')) {
                endExclusive -= 1
                token = text.substring(start, endExclusive)
            }

            // Require at least one dot
            if (!token.contains('.')) continue

            results.add(UrlMatch(start, endExclusive, token))
        }

        // Sort by start index
        results.sortBy { it.start }
        return results
    }
}


