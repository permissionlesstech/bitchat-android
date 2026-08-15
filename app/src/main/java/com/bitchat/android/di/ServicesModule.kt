package com.bitchat.android.di

import android.content.Context
import com.bitchat.android.services.SeenMessageStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Bridges process-wide service singletons into the graph.
 *
 * These stay `getInstance(context)` singletons rather than becoming
 * `@Inject constructor` classes, because they are compiled into `:wear` through
 * the shared-source sync and `:wear` has no Hilt or KSP. Providing them here
 * still lets injectable code depend on the type instead of reaching for the
 * static accessor, which is what makes that code testable.
 *
 * @Singleton matches what the accessor already guarantees; it does not create a
 * second instance.
 */
@Module
@InstallIn(SingletonComponent::class)
object ServicesModule {

    @Provides
    @Singleton
    fun provideSeenMessageStore(
        @ApplicationContext context: Context
    ): SeenMessageStore = SeenMessageStore.getInstance(context)
}
