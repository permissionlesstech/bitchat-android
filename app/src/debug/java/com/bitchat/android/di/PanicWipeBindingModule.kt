package com.bitchat.android.di

import android.content.Context
import com.bitchat.android.panic.SandboxWipe
import com.bitchat.android.panic.SandboxWipeRunner
import com.bitchat.android.testhook.SandboxWipeObserver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

/**
 * Debug builds run the same sandbox wipe, wrapped so a device test can look at the sandbox the
 * moment it returns ([SandboxWipeObserver]). The release source set binds the wipe directly.
 */
@Module
@InstallIn(SingletonComponent::class)
object PanicWipeBindingModule {
    @Provides
    fun provideSandboxWipe(@ApplicationContext context: Context): SandboxWipeRunner {
        val wipe = SandboxWipe(context)
        return SandboxWipeRunner {
            wipe.run().also { result -> SandboxWipeObserver.afterWipe?.invoke(result) }
        }
    }
}
