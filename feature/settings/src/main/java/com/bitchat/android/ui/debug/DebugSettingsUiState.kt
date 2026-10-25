package com.bitchat.android.ui.debug

import com.bitchat.android.services.meshgraph.MeshGraphService
import com.bitchat.android.settings.MeshSnapshot
import com.bitchat.android.settings.WifiAwareStatus
import com.bitchat.android.util.DistributionInfoProvider

/** One direction of per-device or per-peer relay counts, keyed by address or peer ID. */
data class RelayKeyCounts(
    val lastSecond: Map<String, Int> = emptyMap(),
    val lastMinute: Map<String, Int> = emptyMap(),
    val total: Map<String, Long> = emptyMap(),
)

/**
 * Everything Debug shows (inventory B-1). The settings, console, scan results,
 * relay counts and the Wi-Fi Aware lists are the manager's flows; the
 * connected devices and Wi-Fi Aware lists are the mirrors the poll writes.
 */
data class DebugSettingsUiState(
    val verboseLogging: Boolean,
    val gattServerEnabled: Boolean,
    val gattClientEnabled: Boolean,
    val packetRelayEnabled: Boolean,
    val maxConnectionsOverall: Int,
    val maxServerConnections: Int,
    val maxClientConnections: Int,
    val debugMessages: List<DebugMessage>,
    val scanResults: List<DebugScanResult>,
    val connectedDevices: List<ConnectedDevice>,
    val relayStats: PacketRelayStats,
    val seenPacketCapacity: Int,
    val gcsMaxBytes: Int,
    val gcsFprPercent: Double,
    val bleEnabled: Boolean,
    val wifiAwareEnabled: Boolean,
    val wifiAwareVerbose: Boolean,
    /** The manager's mirror: peer ID to nickname, or "". */
    val wifiAwareDiscovered: Map<String, String>,
    /** The manager's mirror: peer ID to IP address. */
    val wifiAwareConnected: Map<String, String>,
    val wifiAwareStatus: WifiAwareStatus,
    /** Wi-Fi Aware's live connected peers, which the graph's markers read (QD13). */
    val wifiAwareLivePeers: Map<String, String>,
    /** The runtime permissions enabling Wi-Fi Aware needs. */
    val wifiAwarePermissions: List<String>,
    val meshGraph: MeshGraphService.GraphSnapshot,
    val perDeviceIncoming: RelayKeyCounts = RelayKeyCounts(),
    val perDeviceOutgoing: RelayKeyCounts = RelayKeyCounts(),
    val perPeerIncoming: RelayKeyCounts = RelayKeyCounts(),
    val perPeerOutgoing: RelayKeyCounts = RelayKeyCounts(),
    /** False when there is no mesh service to show. Assumed true until the mesh is opened. */
    val meshAvailable: Boolean = true,
    val localPeerID: String? = null,
    /** "Our device ID", read once each time the mesh is opened. */
    val localAdapterAddress: String? = null,
    /** The poll's latest mesh read; the relay card's legend reads its lookups. */
    val latestSnapshot: MeshSnapshot? = null,
    /** Null until the installed package has been inspected, or if that failed. */
    val distributionInfo: DistributionInfoProvider.DistributionInfo? = null,
)

/**
 * What the user does on Debug (inventory DA1-DA24). Graph mode (DA14), the
 * legend highlight (DA15) and node drags (DA25) stay in the UI.
 */
sealed interface DebugSettingsAction {
    /** The screen entered composition: open the mesh, start the poll, mark Debug visible. */
    data object ScreenShown : DebugSettingsAction

    /** The screen left composition: stop the poll, mark Debug hidden. */
    data object ScreenHidden : DebugSettingsAction

    /** DA1 */
    data object BackClicked : DebugSettingsAction

    /** DA2. The Root copies it and shows the toast. */
    data class CopyCertificateClicked(val fingerprint: String) : DebugSettingsAction

    /** DA3 */
    data class VerboseLoggingToggled(val enabled: Boolean) : DebugSettingsAction

    /** DA4 */
    data class GattServerToggled(val enabled: Boolean) : DebugSettingsAction

    /** DA5: the slider's raw position. */
    data class MaxServerConnectionsChanged(val value: Float) : DebugSettingsAction

    /** DA6 */
    data class GattClientToggled(val enabled: Boolean) : DebugSettingsAction

    /** DA7 */
    data class MaxClientConnectionsChanged(val value: Float) : DebugSettingsAction

    /** DA8 */
    data class MaxOverallConnectionsChanged(val value: Float) : DebugSettingsAction

    /** DA9 */
    data class BleToggled(val enabled: Boolean) : DebugSettingsAction

    /**
     * DA10 switched on, or DA16 "Start". The Root checks the permissions and
     * asks for the missing ones, then sends [WifiAwareEnableResolved].
     */
    data object WifiAwareEnableClicked : DebugSettingsAction

    /** DA11: the permission check's outcome; enables Wi-Fi Aware, or logs why not. */
    data class WifiAwareEnableResolved(val permitted: Boolean) : DebugSettingsAction

    /** DA10 switched off, or DA17 "Stop". */
    data object WifiAwareDisableClicked : DebugSettingsAction

    /** DA12 */
    data class WifiAwareVerboseToggled(val enabled: Boolean) : DebugSettingsAction

    /** DA13 */
    data class PacketRelayToggled(val enabled: Boolean) : DebugSettingsAction

    /** DA18 */
    data object WifiAwareAnnounceClicked : DebugSettingsAction

    /** DA19: the slider's raw position. */
    data class SeenPacketCapacityChanged(val value: Float) : DebugSettingsAction

    /** DA20: the slider's raw position. */
    data class GcsMaxBytesChanged(val value: Float) : DebugSettingsAction

    /** DA21: the slider's raw position. */
    data class GcsFprChanged(val value: Float) : DebugSettingsAction

    /** DA22 */
    data class DisconnectClicked(val address: String) : DebugSettingsAction

    /** DA23 */
    data class ConnectClicked(val address: String) : DebugSettingsAction

    /** DA24 */
    data object ClearConsoleClicked : DebugSettingsAction
}

sealed interface DebugSettingsEvent {
    data object Close : DebugSettingsEvent
}
