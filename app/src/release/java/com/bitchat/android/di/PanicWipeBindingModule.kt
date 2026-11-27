package com.bitchat.android.di

import android.content.Context
import com.bitchat.android.panic.SandboxWipe
import com.bitchat.android.panic.SandboxWipeRunner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

/** Release builds run the sandbox wipe as it is. Debug builds wrap it for device tests. */
@Module
@InstallIn(SingletonComponent::class)
object PanicWipeBindingModule {
    @Provides
    fun provideSandboxWipe(@ApplicationContext context: Context): SandboxWipeRunner =
        SandboxWipeRunner(SandboxWipe(context)::run)
}
