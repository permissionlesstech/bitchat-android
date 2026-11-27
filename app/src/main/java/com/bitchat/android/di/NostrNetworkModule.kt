package com.bitchat.android.di

import com.bitchat.android.mesh.PowerManager
import com.bitchat.android.net.ArtiTorManager
import com.bitchat.android.net.OkHttpProvider
import com.bitchat.android.net.TorAppBackground
import com.bitchat.android.nostr.NostrHttpClients
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.flow.map

/**
 * The HTTP clients Nostr and the geocoder use: this app's OkHttp clients, which route through Tor
 * when Tor is on. Nothing in `:core:nostr` has a default, so without this binding nothing that
 * talks to a relay compiles.
 */
@Module
@InstallIn(SingletonComponent::class)
object NostrNetworkModule {

    @Provides
    @Singleton
    fun clients(okHttp: OkHttpProvider, tor: ArtiTorManager): NostrHttpClients =
        object : NostrHttpClients {
            override fun httpClient() = okHttp.httpClient()
            override fun webSocketClient() = okHttp.webSocketClient()
            override val routeChanges = tor.routeChanges
            override suspend fun awaitRoute(timeoutMs: Long) = tor.awaitSelectedRoute(timeoutMs)
            override fun routeGeneration() = tor.routeGeneration
        }

    /** Tor goes soft-dormant while the app is in the background, as the power policy says. */
    @Provides
    @Singleton
    fun torAppBackground(power: PowerManager): TorAppBackground =
        object : TorAppBackground {
            override val inBackground = power.profile.map { it.isBackground }
        }
}
