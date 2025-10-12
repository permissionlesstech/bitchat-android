package com.bitchat.android.application

import android.app.Application
import android.content.Context
import com.bitchat.android.mesh.BluetoothMeshDelegate
import com.bitchat.android.mesh.BluetoothMeshDelegateImpl
import com.bitchat.android.mesh.BluetoothMeshService
import kotlinx.coroutines.CoroutineScope

interface AppContainer {
    val meshService : BluetoothMeshService
    val bmd : BluetoothMeshDelegate
}

class AppDataContainer(
    private val scope: CoroutineScope, private val context: Context,
    override val meshService: BluetoothMeshService, private val app: Application
) : AppContainer {
    override val bmd by lazy {
        BluetoothMeshDelegateImpl(scope, context, meshService, app)
    }
}