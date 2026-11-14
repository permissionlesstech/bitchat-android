package com.bitchat.android.di

import com.bitchat.android.panic.AppProcessRestarter
import com.bitchat.android.panic.ClipboardPanicCleanup
import com.bitchat.android.panic.DebugBuffersPanicCleanup
import com.bitchat.android.panic.NotificationsPanicCleanup
import com.bitchat.android.panic.PanicCleanup
import com.bitchat.android.panic.ProcessRestarter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * The effects outside the sandbox that a panic undoes, read by the panic as a set, and the restart
 * it falls back to when the wipe cannot confirm.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PanicCleanupModule {
    @Binds
    @IntoSet
    abstract fun notifications(cleanup: NotificationsPanicCleanup): PanicCleanup

    @Binds
    @IntoSet
    abstract fun clipboard(cleanup: ClipboardPanicCleanup): PanicCleanup

    @Binds
    @IntoSet
    abstract fun debugBuffers(cleanup: DebugBuffersPanicCleanup): PanicCleanup

    @Binds
    abstract fun restarter(restarter: AppProcessRestarter): ProcessRestarter
}
