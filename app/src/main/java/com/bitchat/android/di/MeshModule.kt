package com.bitchat.android.di

import android.content.Context
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.service.MeshServiceHolder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

/**
 * Bridges the process-wide mesh instances into the injection graph.
 *
 * [MeshServiceHolder] remains the single source of truth: it is shared with the
 * foreground service, it synchronises creation, and it supports replacing the
 * mesh service after a panic clear. These bindings are therefore deliberately
 * unscoped — each injection re-reads the holder. Caching them with @Singleton
 * would pin the first instance for the process lifetime and hand out a stale
 * mesh service after a replacement.
 */
@Module
@InstallIn(SingletonComponent::class)
object MeshModule {

    @Provides
    fun provideBluetoothMeshService(
        @ApplicationContext context: Context
    ): BluetoothMeshService = MeshServiceHolder.getOrCreate(context)

    @Provides
    fun provideMeshService(
        @ApplicationContext context: Context
    ): MeshService = MeshServiceHolder.getUnifiedOrCreate(context)
}
