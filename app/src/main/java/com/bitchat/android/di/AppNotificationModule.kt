package com.bitchat.android.di

import com.bitchat.android.MainActivity
import com.bitchat.android.service.ConversationNotificationReceiver
import com.bitchat.android.ui.NotificationTargets
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** This app's notification targets, shared by the chat session and the mesh services. */
val AppNotificationTargets = NotificationTargets(
    activity = MainActivity::class.java,
    actionReceiver = ConversationNotificationReceiver::class.java,
)

@Module
@InstallIn(SingletonComponent::class)
object AppNotificationModule {
    @Provides
    fun provideNotificationTargets(): NotificationTargets = AppNotificationTargets
}
