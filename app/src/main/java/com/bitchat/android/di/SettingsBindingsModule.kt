package com.bitchat.android.di

import android.content.Context
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.onboarding.PermissionManager
import com.bitchat.android.service.MeshForegroundService
import com.bitchat.android.service.MeshServiceHolder
import com.bitchat.android.service.MeshServicePreferences
import com.bitchat.android.settings.BackgroundMeshSetting
import com.bitchat.android.settings.TransportDiagnostics
import com.bitchat.android.wifiaware.WifiAwareController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/** What :feature:settings needs from the app: the background mesh switch and Debug's transports. */
@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsBindingsModule {
    @Binds
    abstract fun bindBackgroundMeshSetting(impl: AppBackgroundMeshSetting): BackgroundMeshSetting

    @Binds
    abstract fun bindTransportDiagnostics(impl: AppTransportDiagnostics): TransportDiagnostics
}

class AppBackgroundMeshSetting @Inject constructor(
    @ApplicationContext private val context: Context,
) : BackgroundMeshSetting {

    override fun isEnabled(): Boolean = MeshServicePreferences.isBackgroundEnabled(true)

    override fun setEnabled(enabled: Boolean) {
        MeshServicePreferences.setBackgroundEnabled(enabled)
        if (!enabled) {
            MeshForegroundService.stop(context)
        } else {
            MeshForegroundService.start(context)
        }
    }
}

@Singleton
class AppTransportDiagnostics @Inject constructor(
    @ApplicationContext private val context: Context,
) : TransportDiagnostics {

    // Looked up on every call rather than held. A panic clear replaces the mesh
    // service, so a held reference could be a stale instance; the holder always
    // has the current one. Never resolved at injection, so nothing here creates
    // the service before Debug first asks for it.
    private val mesh: BluetoothMeshService
        get() = MeshServiceHolder.meshService ?: MeshServiceHolder.getOrCreate(context)

    // Lives as long as the process, like WifiAwareController itself.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    override val wifiAwareSupported: StateFlow<Boolean> = WifiAwareController.supported
    override val wifiAwareAvailable: StateFlow<Boolean> = WifiAwareController.available
    override val wifiAwareSupportReason: StateFlow<String?> = WifiAwareController.supportStatus
        .map { it?.reason }
        .stateIn(scope, SharingStarted.Eagerly, WifiAwareController.supportStatus.value?.reason)
    override val wifiAwareRunning: StateFlow<Boolean> = WifiAwareController.running
    override val wifiAwareConnectedPeers: StateFlow<Map<String, String>> = WifiAwareController.connectedPeers
    override val wifiAwareKnownPeers: StateFlow<Map<String, String>> = WifiAwareController.knownPeers
    override val wifiAwareDiscoveredPeers: StateFlow<Set<String>> = WifiAwareController.discoveredPeers

    override fun sendWifiAwareAnnounce() {
        WifiAwareController.getService()?.sendBroadcastAnnounce()
    }

    override fun wifiAwarePermissions(): List<String> = PermissionManager(context).wifiAwarePermissions()

    override val myPeerID: String get() = mesh.myPeerID
    override fun peerNicknames(): Map<String, String> = mesh.getPeerNicknames()
    override fun deviceAddressToPeerMapping(): Map<String, String> = mesh.getDeviceAddressToPeerMapping()
    override fun isDirectConnection(peerID: String): Boolean = mesh.getPeerInfo(peerID)?.isDirectConnection == true
    override fun connectedDeviceEntries(): List<Triple<String, Boolean, Int?>> =
        mesh.connectionManager.getConnectedDeviceEntries()

    override fun startGattServer() = mesh.connectionManager.startServer()
    override fun stopGattServer() = mesh.connectionManager.stopServer()
    override fun startGattClient() = mesh.connectionManager.startClient()
    override fun stopGattClient() = mesh.connectionManager.stopClient()

    override fun localAdapterAddress(): String? = mesh.connectionManager.getLocalAdapterAddress()

    override fun connectToDevice(address: String) {
        mesh.connectionManager.connectToAddress(address)
    }

    override fun disconnectDevice(address: String) = mesh.connectionManager.disconnectAddress(address)
}
