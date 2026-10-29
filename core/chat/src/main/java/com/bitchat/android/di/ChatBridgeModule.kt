package com.bitchat.android.di

import android.content.Context
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.ConversationListPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

/** Hands today's static `:core:chat` instance to the graph until its class is flipped. */
@Module
@InstallIn(SingletonComponent::class)
object ChatBridgeModule {

    // 10c-2a-1 bridge: deleted when ConversationListPreferences is flipped
    @Provides
    fun provideConversationListPreferences(
        @ApplicationContext context: Context,
        contactDirectory: ContactDirectory,
    ): ConversationListPreferences = ConversationListPreferences.getInstance(context, contactDirectory)
}
