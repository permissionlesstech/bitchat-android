package com.bitchat.android.di

import android.os.SystemClock
import android.util.Log
import com.bitchat.android.ui.PanicStageListener
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Debug builds log how long each of the panic's stages took, so a device check can time them.
 * Stage names and durations only, never paths or identifiers. Release builds bind no listener.
 */
@Module
@InstallIn(SingletonComponent::class)
object PanicStageTimingModule {
    private const val TAG = "PanicStage"

    @Provides
    fun provideStageListener(): PanicStageListener {
        var startedAt = 0L
        var lastAt = 0L
        return PanicStageListener { stage ->
            val now = SystemClock.elapsedRealtime()
            if (stage == "started") {
                startedAt = now
                lastAt = now
            }
            Log.i(TAG, "$stage +${now - lastAt} ms (at ${now - startedAt} ms)")
            lastAt = now
        }
    }
}
