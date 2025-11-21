package com.bitchat.android.net

import jakarta.inject.Inject
import jakarta.inject.Singleton
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Centralized OkHttp provider to ensure all network traffic honors Tor settings.
 */
@Singleton
class OkHttpProvider  @Inject constructor(
    private val torManager: TorManager
) {
    private val httpClientRef = AtomicReference<OkHttpClient?>(null)
    private val wsClientRef = AtomicReference<OkHttpClient?>(null)
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    init {
        scope.launch {
            torManager.statusFlow.collect {
                // Reset clients whenever Tor status changes to ensure we pick up new proxy settings
                reset()
            }
        }
    }


    fun reset() {
        httpClientRef.set(null)
        wsClientRef.set(null)
    }

    fun httpClient(): OkHttpClient {
        httpClientRef.get()?.let { return it }
        val client = baseBuilderForCurrentProxy()
            .callTimeout(15, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        httpClientRef.set(client)
        return client
    }

    fun webSocketClient(): OkHttpClient {
        wsClientRef.get()?.let { return it }
        val client = baseBuilderForCurrentProxy()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
        wsClientRef.set(client)
        return client
    }

    private fun baseBuilderForCurrentProxy(): OkHttpClient.Builder {
        val builder = OkHttpClient.Builder()
        val socks: InetSocketAddress? = torManager.currentSocksAddress()
        // If a SOCKS address is defined, always use it. TorManager sets this as soon as Tor mode is ON,
        // even before bootstrap, to prevent any direct connections from occurring.
        if (socks != null) {
            val proxy = Proxy(Proxy.Type.SOCKS, socks)
            builder.proxy(proxy)
        }
        return builder
    }
}
