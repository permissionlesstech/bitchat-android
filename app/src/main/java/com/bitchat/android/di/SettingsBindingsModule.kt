package com.bitchat.android.di

import android.content.Context
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.onboarding.DeviceReadiness
import com.bitchat.android.service.MeshForegroundService
import com.bitchat.android.service.MeshServiceHolder
import com.bitchat.android.service.MeshServicePreferences
import com.bitchat.android.settings.BackgroundMeshSetting
import com.bitchat.android.settings.MeshDiagnostics
import com.bitchat.android.settings.MeshSnapshot
import com.bitchat.android.settings.TransportDiagnostics
import com.bitchat.android.settings.WifiAwareStatus
import com.bitchat.android.wifiaware.WifiAwareController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

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

class AppTransportDiagnostics @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceReadiness: DeviceReadiness,
) : TransportDiagnostics {

    override val wifiAwareStatus: StateFlow<WifiAwareStatus> = combineStates(
        WifiAwareController.supported,
        WifiAwareController.available,
        WifiAwareController.running,
        WifiAwareController.supportStatus,
    ) { supported, available, running, supportStatus ->
        WifiAwareStatus(supported, available, running, supportStatus?.reason)
    }
    override val wifiAwareConnectedPeers: StateFlow<Map<String, String>> = WifiAwareController.connectedPeers
    override val wifiAwareDiscoveredPeers: StateFlow<Map<String, String>> = combineStates(
        WifiAwareController.discoveredPeers,
        WifiAwareController.knownPeers,
        ::discoveredWithNicknames,
    )

    override fun sendWifiAwareAnnounce() {
        WifiAwareController.getService()?.sendBroadcastAnnounce()
    }

    override fun wifiAwarePermissions(): List<String> = deviceReadiness.wifiAwarePermissions()

    override fun openMesh(): MeshDiagnostics =
        BluetoothMeshDiagnostics(MeshServiceHolder.getOrCreate(context.applicationContext))
}

/** Each discovered peer with the nickname Wi-Fi Aware learned for it, or "". */
internal fun discoveredWithNicknames(discovered: Set<String>, known: Map<String, String>): Map<String, String> =
    discovered.associateWith { pid -> known[pid] ?: "" }

private class BluetoothMeshDiagnostics(private val mesh: BluetoothMeshService) : MeshDiagnostics {
    override val myPeerID: String get() = mesh.myPeerID

    override fun snapshot(): MeshSnapshot {
        val entries = mesh.connectionManager.getConnectedDeviceEntries()
        val mapping = mesh.getDeviceAddressToPeerMapping()
        val nicknames = mesh.getPeerNicknames()
        val directPeers = mapping.values.toSet()
            .filterTo(mutableSetOf()) { pid -> mesh.getPeerInfo(pid)?.isDirectConnection == true }
        return MeshSnapshot.assemble(entries, mapping, nicknames, directPeers)
    }

    override fun setGattServerRunning(on: Boolean) =
        if (on) mesh.connectionManager.startServer() else mesh.connectionManager.stopServer()

    override fun setGattClientRunning(on: Boolean) =
        if (on) mesh.connectionManager.startClient() else mesh.connectionManager.stopClient()

    override fun localAdapterAddress(): String? = mesh.connectionManager.getLocalAdapterAddress()

    override fun connectToDevice(address: String) {
        mesh.connectionManager.connectToAddress(address)
    }

    override fun disconnectDevice(address: String) = mesh.connectionManager.disconnectAddress(address)
}
