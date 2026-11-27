package com.bitchat.android.di

import android.content.Context
import com.bitchat.android.panic.PanicMarker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

/** The panic marker both clients inject. It holds no state beyond a file, so it is not a singleton. Only the phone ever sets it. */
@Module
@InstallIn(SingletonComponent::class)
object PanicModule {

    @Provides
    fun providePanicMarker(@ApplicationContext context: Context): PanicMarker = PanicMarker(context)
}
