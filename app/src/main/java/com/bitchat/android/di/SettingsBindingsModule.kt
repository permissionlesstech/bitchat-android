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
    private val meshServicePreferences: MeshServicePreferences,
) : BackgroundMeshSetting {

    override fun isEnabled(): Boolean = meshServicePreferences.isBackgroundEnabled(true)

    override fun setEnabled(enabled: Boolean) {
        meshServicePreferences.setBackgroundEnabled(enabled)
        if (!enabled) {
            MeshForegroundService.stop(context)
        } else {
            MeshForegroundService.start(context)
        }
    }
}

class AppTransportDiagnostics @Inject constructor(
    private val deviceReadiness: DeviceReadiness,
    private val wifiAwareController: WifiAwareController,
    private val meshServiceHolder: MeshServiceHolder,
) : TransportDiagnostics {

    override val wifiAwareStatus: StateFlow<WifiAwareStatus> = combineStates(
        wifiAwareController.supported,
        wifiAwareController.available,
        wifiAwareController.running,
        wifiAwareController.supportStatus,
    ) { supported, available, running, supportStatus ->
        WifiAwareStatus(supported, available, running, supportStatus?.reason)
    }
    override val wifiAwareConnectedPeers: StateFlow<Map<String, String>> = wifiAwareController.connectedPeers
    override val wifiAwareDiscoveredPeers: StateFlow<Map<String, String>> = combineStates(
        wifiAwareController.discoveredPeers,
        wifiAwareController.knownPeers,
        ::discoveredWithNicknames,
    )

    override fun sendWifiAwareAnnounce() {
        wifiAwareController.getService()?.sendBroadcastAnnounce()
    }

    override fun wifiAwarePermissions(): List<String> = deviceReadiness.wifiAwarePermissions()

    // The handle for the mesh last opened, so a caller sees the same handle until the mesh changes.
    @Volatile
    private var opened: BluetoothMeshDiagnostics? = null

    /**
     * The mesh running now, whether or not the holder would reuse it, or null
     * when none is. Debug never creates or replaces a mesh (B1).
     */
    override fun openMesh(): MeshDiagnostics? {
        val current = meshServiceHolder.meshService ?: return null
        opened?.takeIf { it.mesh === current }?.let { return it }
        return BluetoothMeshDiagnostics(current).also { opened = it }
    }
}

/** Each discovered peer with the nickname Wi-Fi Aware learned for it, or "". */
internal fun discoveredWithNicknames(discovered: Set<String>, known: Map<String, String>): Map<String, String> =
    discovered.associateWith { pid -> known[pid] ?: "" }

private class BluetoothMeshDiagnostics(val mesh: BluetoothMeshService) : MeshDiagnostics {
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
