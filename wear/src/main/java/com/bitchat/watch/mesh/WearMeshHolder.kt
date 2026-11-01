package com.bitchat.watch.mesh

import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Owns the watch's one [WearMeshService]. Creating the mesh loads or generates the identity keys,
 * so it happens only on the first [getOrCreate], which callers make after permissions and
 * Bluetooth are confirmed; injecting the holder creates nothing.
 */
@Singleton
class WearMeshHolder @Inject constructor(
    private val create: Provider<WearMeshService>,
) {
    @Volatile
    private var instance: WearMeshService? = null

    fun getOrCreate(): WearMeshService {
        return instance ?: synchronized(this) {
            instance ?: create.get().also { instance = it }
        }
    }

    /** The mesh if [getOrCreate] has run, else null. */
    fun peek(): WearMeshService? = instance
}
