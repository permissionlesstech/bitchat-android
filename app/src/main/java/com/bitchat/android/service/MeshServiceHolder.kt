package com.bitchat.android.service

import android.content.Context
import com.bitchat.android.mesh.BluetoothMeshService

/**
 * Process-wide holder to share a single BluetoothMeshService instance
 * between the foreground service and UI (MainActivity/ViewModels).
 */
object MeshServiceHolder {
    @Volatile
    var meshService: BluetoothMeshService? = null
        private set

    @Synchronized
    fun getOrCreate(context: Context): BluetoothMeshService {
        val existing = meshService
        if (existing != null) {
            // If the existing instance is healthy, reuse it; otherwise, replace it.
            return try {
                if (existing.isReusable()) existing else {
                    // Best-effort stop before replacing
                    try { existing.stopServices() } catch (_: Exception) {}
                    val created = BluetoothMeshService(context.applicationContext)
                    meshService = created
                    created
                }
            } catch (_: Exception) {
                val created = BluetoothMeshService(context.applicationContext)
                meshService = created
                created
            }
        }
        val created = BluetoothMeshService(context.applicationContext)
        meshService = created
        return created
    }

    @Synchronized
    fun attach(service: BluetoothMeshService) {
        meshService = service
    }
}
