package com.bitchat.android.ui

import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Told as [PanicClear] reaches each of its stages, by name, so a debug build can time them.
 * Release builds bind none. A stage name is a fixed word, never a path or an identifier.
 */
fun interface PanicStageListener {
    fun reached(stage: String)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PanicStageListenerModule {
    @BindsOptionalOf
    abstract fun stageListener(): PanicStageListener
}
