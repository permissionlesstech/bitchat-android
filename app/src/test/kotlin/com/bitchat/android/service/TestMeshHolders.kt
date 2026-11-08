package com.bitchat.android.service

import com.bitchat.android.ui.debug.DebugPreferenceManager
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.mesh.UnifiedMeshService
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.TestMeshStores
import com.bitchat.android.ui.debug.DebugSettingsManager
import com.bitchat.android.wifiaware.WifiAwareController
import com.bitchat.android.wifiaware.WifiAwareMeshService
import dagger.Lazy
import javax.inject.Provider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.mockito.kotlin.mock

/**
 * A fresh [MeshServiceHolder] per test, so no test sees another's mesh. It builds no mesh service:
 * a test that needs one attaches a mock with [MeshServiceHolder.attach].
 */
fun testMeshServiceHolder(): MeshServiceHolder = MeshServiceHolder(
    newBluetooth = object : BluetoothMeshService.Factory {
        override fun create(): BluetoothMeshService = error("tests attach their mesh instead")
    },
    newUnified = object : UnifiedMeshService.Factory {
        override fun create(bluetooth: BluetoothMeshService): UnifiedMeshService =
            error("tests attach their mesh instead")
    },
    transportBridgeService = TransportBridgeService(),
)

/** A fresh [WifiAwareController] per test that never builds a Wi-Fi Aware mesh. */
fun testWifiAwareController(
    meshServiceHolder: MeshServiceHolder = testMeshServiceHolder(),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    appStateStore: AppStateStore = TestMeshStores(mock()).appStateStore,
): WifiAwareController = WifiAwareController(
    newMesh = Provider<WifiAwareMeshService> { error("tests never start Wi-Fi Aware") },
    meshServiceHolder = meshServiceHolder,
    debugSettingsManager = Lazy(lazy { DebugSettingsManager(DebugPreferenceManager()) }::value),
    appStateStore = appStateStore,
    ioDispatcher = ioDispatcher,
)
