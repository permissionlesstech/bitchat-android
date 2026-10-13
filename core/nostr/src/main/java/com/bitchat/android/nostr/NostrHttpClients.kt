package com.bitchat.android.nostr

import androidx.annotation.VisibleForTesting
import okhttp3.OkHttpClient

/**
 * Where the Nostr and geohash layers get their HTTP and WebSocket clients.
 *
 * The app installs one that routes through Tor when Tor is on. Each call asks
 * again rather than caching, because the app rebuilds its clients when the Tor
 * mode changes.
 */
interface NostrHttpClients {
    fun httpClient(): OkHttpClient
    fun webSocketClient(): OkHttpClient
}

/**
 * The installed [NostrHttpClients].
 *
 * Fails closed: until the app installs one, asking for a client throws. A
 * default plain client would quietly send relay traffic around Tor.
 */
object NostrNetwork {
    @Volatile
    private var installed: NostrHttpClients? = null

    fun install(clients: NostrHttpClients) {
        installed = clients
    }

    internal fun clients(): NostrHttpClients = checkNotNull(installed) {
        "No Nostr HTTP clients installed; relay traffic must not bypass the app's network policy"
    }

    @VisibleForTesting
    internal fun uninstallForTesting() {
        installed = null
    }
}
