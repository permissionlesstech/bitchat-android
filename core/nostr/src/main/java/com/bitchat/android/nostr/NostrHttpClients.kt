package com.bitchat.android.nostr

import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient

/**
 * Where the Nostr and geohash layers get their HTTP and WebSocket clients.
 *
 * The app binds one that routes through Tor when Tor is on. Each call asks
 * again rather than caching, because the app rebuilds its clients when the Tor
 * mode changes.
 *
 * Fails closed: this module has no default, so without the app's binding nothing
 * that needs clients compiles. A default plain client would quietly send relay
 * traffic around Tor.
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
