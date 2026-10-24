package com.bitchat.android.nostr

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.Flow
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

    /**
     * Emits whenever the network route changes, for example when Tor turns on
     * or off or moves to another SOCKS port. On each emission every relay
     * socket opened on the old route is closed, and relays reconnect through
     * the clients this interface hands out next.
     *
     * That reset runs on the relay manager's own coroutine after the emission,
     * so callers must not assume relays have reset when the mode change
     * returns; the app resets its OkHttp clients before emitting, so new
     * connections already use the new route.
     */
    val routeChanges: Flow<Unit>
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

    internal fun installedOrNull(): NostrHttpClients? = installed

    internal fun clients(): NostrHttpClients = checkNotNull(installed) {
        "No Nostr HTTP clients installed; relay traffic must not bypass the app's network policy"
    }

    @VisibleForTesting
    internal fun uninstallForTesting() {
        installed = null
    }
}
