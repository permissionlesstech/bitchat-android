package com.bitchat.android.di

import android.content.Context
import com.bitchat.android.identity.scope.IdentityComponent
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.service.MeshServiceHolder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext

/**
 * Binds the identity's mesh instances inside its component; `AppIdentityBindings` hands them to
 * the process-wide graph.
 *
 * [MeshServiceHolder] remains the single source of truth: it is shared with the
 * foreground service, it synchronises creation, and it replaces a mesh the service
 * stopped. These bindings are therefore deliberately unscoped — each injection
 * re-reads the holder. Caching them with a scope would pin the first instance and
 * hand out a stale mesh service after a replacement.
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
}
