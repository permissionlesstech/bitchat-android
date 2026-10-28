package com.bitchat.android.nostr.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Nothing is left to bridge: every Nostr singleton has an @Inject constructor. */
@Module
@InstallIn(SingletonComponent::class)
object NostrBridgeModule
