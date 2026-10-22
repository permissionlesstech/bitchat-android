package com.bitchat.android.nostr

import java.net.URI

/**
 * Normalize a Nostr relay URL the way iOS [NostrRelayURL] does.
 *
 * Bare hostnames get a default scheme (usually `wss`). Only websocket/http
 * schemes survive; hosts are lowercased; default ports and a lone `/` path
 * are stripped so stored values compare exactly against connection keys.
 */
object NostrRelayURL {
    fun normalized(rawValue: String, defaultScheme: String? = null): String? {
        var value = rawValue.trim()
        if (value.isEmpty()) return null

        if (!value.contains("://") && defaultScheme != null) {
            value = "$defaultScheme://$value"
        }

        val uri = try {
            URI(value)
        } catch (_: Exception) {
            return null
        }

        val rawScheme = uri.scheme?.lowercase() ?: return null
        val rawHost = uri.host?.lowercase() ?: return null
        if (rawHost.isEmpty()) return null

        val (scheme, dropPort) = when (rawScheme) {
            "wss", "https" -> "wss" to 443
            "ws", "http" -> "ws" to 80
            else -> return null
        }

        val port = uri.port.takeIf { it > 0 && it != dropPort }
        var path = uri.path.orEmpty()
        if (path == "/") path = ""
        val query = uri.query?.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
        val portPart = port?.let { ":$it" }.orEmpty()

        return "$scheme://$rawHost$portPart$path$query"
    }
}
