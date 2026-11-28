package com.bitchat.android.nostr

/**
 * Configuration helper for opt-in Nostr blaster relay broadcasting (issue #495).
 */
object BlasterRelayConfig {

    const val DEFAULT_BLASTER_RELAY_URL: String = "wss://nostr.mutinywallet.com"

    /**
     * Merges the blaster relay URL into an existing relay list when enabled,
     * avoiding duplicates regardless of trailing slashes or casing.
     */
    fun applyBlasterRelay(
        relays: List<String>,
        enabled: Boolean,
        blasterUrl: String = DEFAULT_BLASTER_RELAY_URL
    ): List<String> {
        if (!enabled) return relays
        val normalizedTarget = normalize(blasterUrl)
        if (relays.any { normalize(it) == normalizedTarget }) {
            return relays
        }
        return relays + blasterUrl.trim()
    }

    private fun normalize(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd < 0) return trimmed.lowercase()
        val authorityStart = schemeEnd + 3
        val pathStart = trimmed.indexOfAny(charArrayOf('/', '?', '#'), startIndex = authorityStart)
        val hostEnd = if (pathStart < 0) trimmed.length else pathStart
        val schemeAndHost = trimmed.substring(0, hostEnd).lowercase()
        val rest = if (pathStart < 0) "" else trimmed.substring(pathStart)
        return schemeAndHost + rest
    }
}
