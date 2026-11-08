package com.bitchat.android.di

import android.content.Context
import com.bitchat.android.identity.scope.IdentityComponent
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.service.MeshServiceHolder
import com.bitchat.android.ui.SessionMeshSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext

/**
 * Binds the identity's mesh instances inside its component; `AppIdentityBindings` hands them to
 * the process-wide graph.
 *
 * [MeshServiceHolder] remains the single source of truth: it is shared with the
 * foreground service, it synchronises creation, and it supports replacing the
 * mesh service after a panic clear. These bindings are therefore deliberately
 * unscoped — each injection re-reads the holder. Caching them with a scope
 * would pin the first instance and hand out a stale mesh service after a
 * replacement.
 *
 * `BluetoothMeshService` itself has no binding: it is built only through its
 * assisted factory, which only the holder takes, so injecting it directly does
 * not compile. Code that needs the Bluetooth mesh asks the holder.
 */
@Module
@InstallIn(IdentityComponent::class)
object MeshModule {

    @Provides
    fun provideMeshService(
        @ApplicationContext context: Context,
        meshServiceHolder: MeshServiceHolder,
    ): MeshService = meshServiceHolder.getUnifiedOrCreate(context)

    /** Panic clear's restart: exactly the holder calls it made, in the same order. */
    @Provides
    fun provideSessionMeshSource(
        @ApplicationContext context: Context,
        meshServiceHolder: MeshServiceHolder,
    ): SessionMeshSource = object : SessionMeshSource {
        override fun recreate(): MeshService {
            // Clear the holder so getOrCreate() returns a fresh instance
            meshServiceHolder.clear()
            meshServiceHolder.getOrCreate(context)
            return meshServiceHolder.getUnifiedOrCreate(context)
        }
    }
}
