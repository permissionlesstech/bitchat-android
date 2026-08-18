package com.bitchat.android.di

import android.content.Context
import com.bitchat.android.geohash.GeohashBookmarksStore
import com.bitchat.android.geohash.LocationChannelManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Bridges the geohash singletons into the graph.
 *
 * Both hold location state and are process-wide today. Injecting them as
 * dagger.Lazy at the call site preserves the current behaviour of not touching
 * them until something actually needs a channel or a bookmark.
 */
@Module
@InstallIn(SingletonComponent::class)
object GeohashModule {

    @Provides
    @Singleton
    fun provideLocationChannelManager(
        @ApplicationContext context: Context
    ): LocationChannelManager = LocationChannelManager.getInstance(context)

    @Provides
    @Singleton
    fun provideGeohashBookmarksStore(
        @ApplicationContext context: Context
    ): GeohashBookmarksStore = GeohashBookmarksStore.getInstance(context)
}
