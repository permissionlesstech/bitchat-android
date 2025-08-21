package com.bitchat.android.mesh

import android.content.Context

/**
 * Holds a single shared instance of BluetoothMeshService so the app UI
 * and background service can operate on the same mesh without duplication.
 */
object MeshServiceHolder {
    @Volatile
    private var instance: BluetoothMeshService? = null

    fun get(context: Context): BluetoothMeshService {
        return instance ?: synchronized(this) {
            instance ?: BluetoothMeshService(context.applicationContext).also { instance = it }
        }
    }
}

