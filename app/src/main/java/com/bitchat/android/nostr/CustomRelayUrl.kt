package com.bitchat.android.nostr

import java.net.URI

object CustomRelayUrl {
    fun normalize(input: String): String? = runCatching {
        val uri = URI(input.trim())
        require(uri.scheme.equals("wss", ignoreCase = true))
        require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null && uri.query == null)
        require(uri.port == -1 || uri.port in 1..65535)
        URI("wss", null, uri.host.lowercase(), uri.port,
            uri.path?.takeUnless { it == "/" }, null, null).toASCIIString()
    }.getOrNull()
}
