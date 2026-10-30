package com.bitchat.android.ui.debug

import com.bitchat.android.services.meshgraph.MeshGraphService
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** The gossip topology Debug's mesh graph draws. */
interface MeshGraphSource {
    val graphState: StateFlow<MeshGraphService.GraphSnapshot>
}

internal class MeshGraphServiceSource @Inject constructor(
    meshGraphService: MeshGraphService,
) : MeshGraphSource {
    override val graphState: StateFlow<MeshGraphService.GraphSnapshot> =
        meshGraphService.graphState
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class MeshGraphSourceModule {
    @Binds
    abstract fun bindMeshGraphSource(impl: MeshGraphServiceSource): MeshGraphSource
}
