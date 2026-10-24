package com.bitchat.android.di

import android.content.Context
import com.bitchat.android.nostr.NostrTransport
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Bridges the Nostr transport into the graph.
 *
 * Inject it as dagger.Lazy. Resolving it eagerly would construct the transport
 * during ViewModel creation, which is earlier than the current call sites do it.
 */
@Module
@InstallIn(SingletonComponent::class)
object NostrModule {

    @Provides
    @Singleton
    fun provideNostrTransport(
        @ApplicationContext context: Context
    ): NostrTransport = NostrTransport.getInstance(context)
}
