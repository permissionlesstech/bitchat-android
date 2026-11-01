package com.bitchat.android.ui.debug

import com.bitchat.android.services.meshgraph.MeshGraphService
import com.bitchat.android.settings.WifiAwareStatus
import com.bitchat.android.sync.GCSFilter
import com.bitchat.android.util.DistributionInfoProvider

// Debug settings' pure decisions, by inventory id (D1 is MeshSnapshot.assemble,
// D2 is the app's wifiAwareDiscoveredPeers, D14-D15 and D19-D20 are RelayGraphSampler).

/** D3: the peers with a Bluetooth connection, for the graph's edge markers. */
internal fun blePeerIDs(devices: List<ConnectedDevice>): Set<String> =
    devices.mapNotNull { it.peerID }.toSet()

/** D4: connection counts as the GATT card shows them. */
internal data class ConnectionCounts(val server: Int, val client: Int, val overall: Int)

internal fun connectionCounts(devices: List<ConnectedDevice>): ConnectionCounts = ConnectionCounts(
    server = devices.count { it.connectionType == ConnectionType.GATT_SERVER },
    client = devices.count { it.connectionType == ConnectionType.GATT_CLIENT },
    overall = devices.size,
)

/** D5: a connection-limit slider's value as the manager is given it (it clamps to 1..32 itself). */
internal fun connectionLimitFromSlider(value: Float): Int = value.toInt().coerceAtLeast(1)

/** D6: the Transports card's Wi-Fi Aware label. */
internal fun wifiAwareTransportLabel(status: WifiAwareStatus): String = when {
    !status.supported -> "unsupported"
    status.available -> "available"
    else -> "unavailable"
}

/** D6: the Transports card's Wi-Fi Aware switch shows on only if it is enabled and supported. */
internal fun wifiAwareSwitchChecked(enabled: Boolean, status: WifiAwareStatus): Boolean =
    enabled && status.supported

/** D7: the Wi-Fi Aware card's status. Running beats availability here, unlike D6. */
internal fun wifiAwareCardStatus(status: WifiAwareStatus): String = when {
    !status.supported -> "unsupported"
    status.running -> "running"
    !status.available -> "unavailable"
    else -> "stopped"
}

/** D8: why Wi-Fi Aware is unsupported, or null when it is supported. */
internal fun wifiAwareUnsupportedText(status: WifiAwareStatus): String? =
    if (status.supported) null else status.unsupportedReason ?: "Wi-Fi Aware is not supported on this device"

/** D10: the permissions to request before enabling Wi-Fi Aware. */
internal fun missingPermissions(required: List<String>, isGranted: (String) -> Boolean): List<String> =
    required.filter { !isGranted(it) }

/**
 * D10: after the permission request, whether Wi-Fi Aware can be enabled. Only
 * Nearby devices blocks it; the result map is ignored and live state checked.
 */
internal fun canEnableWifiAwareAfterRequest(required: List<String>, isGranted: (String) -> Boolean): Boolean {
    val nearby = android.Manifest.permission.NEARBY_WIFI_DEVICES
    return nearby !in required || isGranted(nearby)
}

/** D11: the discovered list's lines, at most 50. */
internal fun wifiAwareDiscoveredLines(discovered: Map<String, String>): List<String> =
    discovered.entries.take(50).map { (peer, nick) ->
        "• ${if (nick.isBlank()) peer.take(8) + "…" else nick} (${peer.take(8)}…) "
    }

/** D12: the connected list's lines, at most 50. */
internal fun wifiAwareConnectedLines(connected: Map<String, String>): List<String> =
    connected.entries.take(50).map { (peer, ip) -> "• ${peer.take(8)}… @ $ip" }

/** D13: the relay card's header lines. */
internal fun relayIncomingHeader(stats: PacketRelayStats): String =
    "${stats.lastSecondIncoming}/s • ${stats.lastMinuteIncoming}/m • ${stats.last15MinuteIncoming}/15m • total ${stats.totalIncomingCount}"

internal fun relayOutgoingHeader(stats: PacketRelayStats): String =
    "${stats.lastSecondOutgoing}/s • ${stats.lastMinuteOutgoing}/m • ${stats.last15MinuteOutgoing}/15m • total ${stats.totalOutgoingCount}"

/**
 * D16: a legend entry's title. Per peer: nickname and peer prefix. Per device:
 * the address, with the peer from the connected devices or else [devicePeerMap].
 */
internal fun legendTitle(
    mode: GraphMode,
    key: String,
    nicknames: Map<String, String?>,
    connectedDevices: List<ConnectedDevice>,
    devicePeerMap: Map<String, String>,
): String = when (mode) {
    GraphMode.PER_PEER -> {
        val nick = nicknames[key]
        val prefix = key.take(6)
        if (!nick.isNullOrBlank()) "$nick ($prefix)" else prefix
    }
    GraphMode.PER_DEVICE -> {
        val pid = connectedDevices.firstOrNull { it.deviceAddress == key }?.peerID ?: devicePeerMap[key]
        if (pid != null) {
            val nick = nicknames[pid]
            val prefix = pid.take(6)
            "$key (${if (!nick.isNullOrBlank()) "$nick ($prefix)" else prefix})"
        } else {
            key
        }
    }
    GraphMode.OVERALL -> key
}

/** D17: a legend entry's counts, from the maps for the mode and direction; empty for Overall. */
internal fun legendMetrics(
    mode: GraphMode,
    key: String,
    lastSecond: Map<String, Int>,
    lastMinute: Map<String, Int>,
    total: Map<String, Long>,
): String = when (mode) {
    GraphMode.OVERALL -> ""
    else -> "${lastSecond[key] ?: 0}/s • ${lastMinute[key] ?: 0}/m • total ${total[key] ?: 0L}"
}

/** D18: tapping a legend entry highlights it, or clears the highlight when it already has it. */
internal fun toggleHighlight(highlighted: String?, key: String): String? =
    if (highlighted == key) null else key

/** D18: an entry is dimmed while another one is highlighted. */
internal fun isDimmed(highlighted: String?, key: String): Boolean =
    highlighted != null && highlighted != key

/** D21: the GCS parameter p and the element estimate, from the target FPR in percent. */
internal data class GcsDerived(val p: Int, val maxElements: Int)

internal fun gcsDerived(fprPercent: Double, maxBytes: Int): GcsDerived {
    val p = GCSFilter.deriveP(fprPercent / 100.0)
    return GcsDerived(p, GCSFilter.estimateMaxElementsForSize(maxBytes, p))
}

/** D22: the console's lines, newest first, at most 100. */
internal fun consoleLines(messages: List<DebugMessage>): List<String> =
    messages.takeLast(100).reversed().map { "${it.content}" }

/** D23, D24: a device row's title. */
internal fun deviceRowTitle(peerID: String?, address: String, unknown: String): String =
    (peerID ?: unknown) + " • $address"

/** D26: a topology node's label. */
internal fun topologyLabel(node: MeshGraphService.GraphNode): String =
    "${node.peerID.take(8)} • ${node.nickname ?: "Unknown"}"

/** D27: the distribution rows, label to value, in order; the installer only when known. */
internal fun distributionRows(info: DistributionInfoProvider.DistributionInfo): List<Pair<String, String>> = buildList {
    add("Install source" to info.installSource)
    info.installerPackage?.let { add("Installer package" to it) }
    add("Package format" to info.packageFormat)
    add("APK architecture" to info.architecture)
    add("Sharing source" to info.sharingSource)
    add("Version" to "${info.versionName} (${info.versionCode})")
    add("Signing channel" to info.signingChannel)
    add("Certificate SHA-256" to (info.certificateSha256 ?: "Unavailable"))
}
