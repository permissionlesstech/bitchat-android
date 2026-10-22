package com.bitchat.android.net

import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Centralized OkHttp provider to ensure all network traffic honors Tor settings.
 */
object OkHttpProvider {
    enum class Route {
        DIRECT,
        TOR
    }

    data class RoutedClient(
        val client: OkHttpClient,
        val route: Route
    )

    private val httpClientRef = AtomicReference<RoutedClient?>(null)
    private val wsClientRef = AtomicReference<OkHttpClient?>(null)
    private val clientLock = Any()

    fun reset() {
        synchronized(clientLock) {
            httpClientRef.set(null)
            wsClientRef.set(null)
        }
    }

    fun httpClient(): OkHttpClient = routedHttpClient().client

    /**
     * Returns the client and the route it was actually built with as one snapshot.
     *
     * The selected Tor mode can change while an existing client is still cached. Consumers that
     * key cooldowns by network identity must use this value rather than re-reading the preference.
     */
    fun routedHttpClient(): RoutedClient {
        httpClientRef.get()?.let { return it }
        return synchronized(clientLock) {
            httpClientRef.get() ?: run {
                val (builder, route) = baseBuilderForCurrentProxy()
                val client = builder
                    .callTimeout(15, TimeUnit.SECONDS)
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()
                RoutedClient(client, route).also(httpClientRef::set)
            }
        }
    }

    fun webSocketClient(): OkHttpClient {
        wsClientRef.get()?.let { return it }
        return synchronized(clientLock) {
            wsClientRef.get() ?: baseBuilderForCurrentProxy().first
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build()
                .also(wsClientRef::set)
        }
    }

    private fun baseBuilderForCurrentProxy(): Pair<OkHttpClient.Builder, Route> {
        val builder = OkHttpClient.Builder()
        val torProvider = ArtiTorManager.getInstance()
        val socks: InetSocketAddress? = torProvider.currentSocksAddress()
        // If a SOCKS address is defined, always use it. TorProvider sets this as soon as Tor mode is ON,
        // even before bootstrap, to prevent any direct connections from occurring.
        if (socks != null) {
            val proxy = Proxy(Proxy.Type.SOCKS, socks)
            builder.proxy(proxy)
        }
        return builder to if (socks == null) Route.DIRECT else Route.TOR
    }
}
