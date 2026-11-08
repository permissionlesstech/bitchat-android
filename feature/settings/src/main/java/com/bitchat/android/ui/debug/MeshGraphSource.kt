package com.bitchat.android.ui.debug

import com.bitchat.android.identity.scope.CurrentIdentity
import com.bitchat.android.services.meshgraph.MeshGraphService
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.flow.StateFlow

/** The gossip topology Debug's mesh graph draws. */
interface MeshGraphSource {
    val graphState: StateFlow<MeshGraphService.GraphSnapshot>
}

internal class MeshGraphServiceSource @Inject constructor(
    @CurrentIdentity private val meshGraphService: Provider<MeshGraphService>,
) : MeshGraphSource {
    override val graphState: StateFlow<MeshGraphService.GraphSnapshot>
        get() = meshGraphService.get().graphState
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class MeshGraphSourceModule {
    @Binds
    abstract fun bindMeshGraphSource(impl: MeshGraphServiceSource): MeshGraphSource
}
