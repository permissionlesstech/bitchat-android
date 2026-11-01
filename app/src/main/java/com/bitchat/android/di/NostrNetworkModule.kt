package com.bitchat.android.di

import com.bitchat.android.net.ArtiTorManager
import com.bitchat.android.net.OkHttpProvider
import com.bitchat.android.nostr.NostrHttpClients
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

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
        }
}
