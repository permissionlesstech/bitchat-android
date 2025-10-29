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
        if (existing != null) return existing
        val created = BluetoothMeshService(context.applicationContext)
        meshService = created
        return created
    }

    @Synchronized
    fun attach(service: BluetoothMeshService) {
        meshService = service
    }
}

