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

    private fun normalize(url: String): String =
        url.trim().trimEnd('/').lowercase()
}
