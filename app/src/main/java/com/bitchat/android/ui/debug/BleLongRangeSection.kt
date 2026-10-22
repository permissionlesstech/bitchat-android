package com.bitchat.android.ui.debug

import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.mesh.BleCodedCoding
import com.bitchat.android.mesh.BleLinkDiagnostics
import com.bitchat.android.mesh.BleLinkDiagnostics.AdvertiserState
import com.bitchat.android.mesh.BleLinkGrouping
import com.bitchat.android.mesh.BleLongRangeMode
import com.bitchat.android.mesh.BleLongRangePolicy
import com.bitchat.android.mesh.BleRangeIndicator
import com.bitchat.android.mesh.PhyHysteresis
import com.bitchat.android.mesh.PowerManager
import com.bitchat.android.ui.theme.BitchatFontFamily
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CodedColor = Color(0xFF00BCD4)
private val OkColor = Color(0xFF00C851)
private val WarnColor = Color(0xFFFF9500)
private val ErrorColor = Color(0xFFBF1A1A)

/**
 * Long-range (LE Coded PHY) controls and live radio diagnostics, grouped as: status, mode,
 * coding, this phone, discovery, thresholds, links, actions and radio events.
 */
@Composable
fun BleLongRangeSection(
    manager: DebugSettingsManager,
    peerForAddress: (String) -> String?,
    onReapplyPhy: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val mode by manager.bleLongRangeMode.collectAsState()
    val coding by manager.bleCodedCoding.collectAsState()
    var snapshot by remember { mutableStateOf(BleLinkDiagnostics.snapshot()) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            snapshot = BleLinkDiagnostics.snapshot()
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val indicator = BleRangeIndicator.from(snapshot)
    val caps = snapshot.capabilities
    val longRangeUsable = mode != BleLongRangeMode.OFF && BleLongRangePolicy.supportsLongRangeDiscovery(caps)
    val powerMode = remember(now) { PowerManager.getInstance(context).profile.value.mode }

    Surface(shape = RoundedCornerShape(12.dp), color = colorScheme.surfaceVariant.copy(alpha = 0.2f)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.SettingsInputAntenna, contentDescription = null, tint = CodedColor)
                Text("Long range (LE Coded PHY)", fontFamily = BitchatFontFamily, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            StatusBanner(indicator, snapshot, coding)

            // Mode
            SubHeader("Mode")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BleLongRangeMode.entries.forEach { option ->
                    FilterChip(
                        selected = mode == option,
                        onClick = { manager.setBleLongRangeMode(option) },
                        label = { Text(modeLabel(option), fontFamily = BitchatFontFamily) }
                    )
                }
            }
            Hint(
                when (mode) {
                    BleLongRangeMode.OFF ->
                        "Standard BLE only: legacy advertising and 1M scanning, no PHY requests. Peers may still move a link to Coded."
                    BleLongRangeMode.ADAPTIVE ->
                        "Long-range discovery. Links start on the PHY they were found on; the client moves them to Coded below " +
                            "${PhyHysteresis.ENTER_CODED_BELOW_DBM} dBm and back to 1M above ${PhyHysteresis.LEAVE_CODED_ABOVE_DBM} dBm (smoothed, 10 s dwell)."
                    BleLongRangeMode.MAX_RANGE ->
                        "Long-range discovery, and Coded requested on every link right after setup (retried once if the link leaves Coded)."
                }
            )

            // Coding
            SubHeader("Coded coding (connections)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BleCodedCoding.entries.forEach { option ->
                    FilterChip(
                        selected = coding == option,
                        enabled = longRangeUsable,
                        onClick = { manager.setBleCodedCoding(option) },
                        label = { Text(option.label, fontFamily = BitchatFontFamily) }
                    )
                }
            }
            InfoRow("S=8", "125 kbit/s • ~4× the range of 1M • most robust")
            InfoRow("S=2", "500 kbit/s • ~2× the range of 1M")
            InfoRow("1M (reference)", "1000 kbit/s • standard range")
            Hint(
                "This is a request to the Bluetooth chip. The other phone can pick a different coding for what it sends, " +
                    "and Android only reports a link as \"Coded\", never S=2 or S=8. Advertising coding is chosen by the chip " +
                    "(Android has no setting for it)."
            )

            // This phone
            SubHeader("This phone")
            if (!snapshot.capabilitiesKnown) {
                Hint("Not read yet: capabilities are read when Bluetooth mesh starts.")
            } else {
                CapabilityRow("LE Coded PHY", caps.leCodedPhy)
                CapabilityRow("Extended adv", caps.leExtendedAdvertising)
                CapabilityRow("LE 2M PHY", caps.le2MPhy)
                CapabilityRow("Periodic adv", caps.lePeriodicAdvertising)
                CapabilityRow("Multiple advertisers", caps.multipleAdvertisement)
                InfoRow("Max adv data", "${caps.maxAdvertisingDataLength} bytes (needs ${BleLongRangePolicy.CODED_ADVERTISEMENT_BYTES})")
                if (!BleLongRangePolicy.supportsLongRangeDiscovery(caps)) {
                    Hint("Long-range discovery needs LE Coded PHY and extended advertising: this phone stays on standard BLE.", WarnColor)
                }
            }

            // Discovery
            SubHeader("Discovery")
            InfoRow(
                "Legacy advert (1M)",
                advertiserText(snapshot.legacyAdvertiser, snapshot.legacyAdvertiserError),
                advertiserColor(snapshot.legacyAdvertiser)
            )
            val codedExtras = if (snapshot.codedAdvertiser == AdvertiserState.ACTIVE) {
                listOfNotNull(
                    snapshot.codedAdvertiserIntervalMs?.let { "every $it ms" },
                    snapshot.codedAdvertiserTxPowerDbm?.let { "$it dBm (chip-selected)" }
                )
            } else {
                emptyList()
            }
            InfoRow(
                "Coded advert",
                (listOf(advertiserText(snapshot.codedAdvertiser, snapshot.codedAdvertiserError)) + codedExtras)
                    .joinToString(" • "),
                advertiserColor(snapshot.codedAdvertiser)
            )
            InfoRow(
                "Scanning",
                when (snapshot.scanMode) {
                    BleLinkDiagnostics.ScanMode.STOPPED -> "idle (duty-cycle off window)"
                    BleLinkDiagnostics.ScanMode.LEGACY_1M -> "legacy, 1M only"
                    BleLinkDiagnostics.ScanMode.EXTENDED_ALL_PHYS -> "extended, 1M + Coded"
                } + if (snapshot.extendedScanRejected) " • extended rejected by phone" else "",
                if (snapshot.extendedScanRejected) WarnColor else null
            )
            InfoRow("Adverts heard", "legacy ${snapshot.scanResultsLegacy} • Coded ${snapshot.scanResultsCoded}")
            InfoRow(
                "Last Coded advert",
                snapshot.lastCodedAt?.let { at ->
                    "${snapshot.lastCodedRssi ?: "?"} dBm • ${ageText(now - at)} ago"
                } ?: "none yet"
            )

            // Thresholds
            SubHeader("Thresholds (power mode: ${powerMode.name.lowercase()})")
            InfoRow(
                "Min RSSI connect",
                "Coded ${BleLongRangePolicy.connectRssiThreshold(mode, powerMode, heardOnCoded = true)} dBm • " +
                    "1M ${BleLongRangePolicy.connectRssiThreshold(mode, powerMode, heardOnCoded = false)} dBm"
            )
            InfoRow(
                "Adaptive switch",
                "to Coded < ${PhyHysteresis.ENTER_CODED_BELOW_DBM} dBm • to 1M > ${PhyHysteresis.LEAVE_CODED_ABOVE_DBM} dBm"
            )

            // Devices: entries grouped per peer into real radio connections
            val peers = BleLinkGrouping.group(snapshot.links)
            val connectionCount = peers.sumOf { it.connections.size }
            SubHeader("Connected devices (${peers.size}) • radio connections ($connectionCount)")
            if (peers.isEmpty()) {
                Hint("No BLE connections.")
            } else {
                Hint(
                    "Bitchat connects both ways, so a device normally has 2 radio connections: outgoing " +
                        "(we connected) and incoming (it connected). Android also reports our outgoing " +
                        "connection as a server link; that mirror is merged into the outgoing one."
                )
            }
            peers.forEach { peer ->
                val name = peer.addresses.firstNotNullOfOrNull(peerForAddress) ?: peer.peerID?.take(8)
                PeerCard(peer, name, now)
            }

            // Actions
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ActionText("Re-request PHY", enabled = mode != BleLongRangeMode.OFF, onClick = onReapplyPhy)
                ActionText("Clear events", onClick = { BleLinkDiagnostics.clearEvents() })
            }

            // Events
            SubHeader("Radio events")
            val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .background(colorScheme.surface.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                if (snapshot.events.isEmpty()) Hint("No events yet")
                snapshot.events.takeLast(40).reversed().forEach { event ->
                    Text(
                        "${timeFormat.format(Date(event.timestamp))} ${event.text}",
                        fontFamily = BitchatFontFamily,
                        fontSize = 11.sp,
                        color = if (event.text.contains("fail", ignoreCase = true) ||
                            event.text.contains("rejected", ignoreCase = true)
                        ) WarnColor else colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBanner(indicator: BleRangeIndicator, snapshot: BleLinkDiagnostics.Snapshot, coding: BleCodedCoding) {
    val (color, title) = when (indicator.kind) {
        BleRangeIndicator.Kind.LONG_RANGE -> CodedColor to "LONG RANGE ACTIVE"
        BleRangeIndicator.Kind.STANDARD_OFF -> MaterialTheme.colorScheme.onSurfaceVariant to "STANDARD BLE (long range off)"
        BleRangeIndicator.Kind.STANDARD_UNSUPPORTED -> WarnColor to "STANDARD BLE (not supported)"
        BleRangeIndicator.Kind.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant to "BLUETOOTH NOT STARTED"
    }
    val connections = BleLinkGrouping.group(snapshot.links).flatMap { it.connections }.map { it.link }
    val known = connections.filter { it.txPhy != 0 }
    val coded = known.count { it.txPhy == BluetoothDevice.PHY_LE_CODED && it.rxPhy == BluetoothDevice.PHY_LE_CODED }
    val detail = buildList {
        if (indicator.kind == BleRangeIndicator.Kind.LONG_RANGE) add("requesting ${coding.label}")
        add(
            if (known.isEmpty()) "no connections"
            else "$coded/${known.size} connections on Coded"
        )
        add("est. ~${indicator.estimatedRangeMeters.first}–${indicator.estimatedRangeMeters.last} m")
    }.joinToString(" • ")
    Surface(shape = RoundedCornerShape(8.dp), color = color.copy(alpha = 0.12f)) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(color, CircleShape))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(title, fontFamily = BitchatFontFamily, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color)
                Text(detail, fontFamily = BitchatFontFamily, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun PeerCard(peer: BleLinkGrouping.Peer, name: String?, now: Long) {
    val colorScheme = MaterialTheme.colorScheme
    val allCoded = peer.connections.all {
        it.link.txPhy == BluetoothDevice.PHY_LE_CODED && it.link.rxPhy == BluetoothDevice.PHY_LE_CODED
    }
    Surface(shape = RoundedCornerShape(8.dp), color = colorScheme.surface.copy(alpha = 0.6f)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                (name ?: "unidentified device") + " • ${peer.connections.size} radio connection" +
                    if (peer.connections.size == 1) "" else "s",
                fontFamily = BitchatFontFamily, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = if (allCoded) CodedColor else colorScheme.onSurface
            )
            if (peer.peerID == null) {
                Hint("Not identified yet: no announce received on this connection.")
            }
            peer.connections.forEach { connection ->
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = colorScheme.outline.copy(alpha = 0.2f))
                ConnectionRows(connection, now)
            }
        }
    }
}

@Composable
private fun ConnectionRows(connection: BleLinkGrouping.Connection, now: Long) {
    val colorScheme = MaterialTheme.colorScheme
    val link = connection.link
    val txCoded = link.txPhy == BluetoothDevice.PHY_LE_CODED
    val rxCoded = link.rxPhy == BluetoothDevice.PHY_LE_CODED
    Text(
        (if (connection.outgoing) "↗ Outgoing (we connected)" else "↙ Incoming (it connected)") + " • ${link.state}",
        fontFamily = BitchatFontFamily, fontSize = 12.sp, fontWeight = FontWeight.Medium
    )
    Text(
        link.address + if (connection.mirror != null) " • + Android server-side mirror" else "",
        fontFamily = BitchatFontFamily, fontSize = 10.sp, color = colorScheme.onSurfaceVariant
    )
    // Android never reports S=2 vs S=8 for a live link; show what this side asked for.
    val request = link.lastPhyRequest ?: connection.mirror?.lastPhyRequest
    val requestedCoding = request?.takeIf { it.startsWith("Coded ") }?.removePrefix("Coded ")
    InfoRow(
        "PHY now",
        "TX ${phyText(link.txPhy)} • RX ${phyText(link.rxPhy)}" +
            if (txCoded || rxCoded) {
                " (" + (requestedCoding?.let { "$it requested" } ?: "coding not reported") + ")"
            } else "",
        if (txCoded && rxCoded) CodedColor else null
    )
    val requests = link.phyRequests + (connection.mirror?.phyRequests ?: 0)
    InfoRow(
        "Requested",
        (request ?: "nothing (kept as negotiated)") + if (requests > 0) " • ${requests}×" else ""
    )
    (link.lastPhyStatus ?: connection.mirror?.lastPhyStatus)?.takeIf { it != 0 }?.let { status ->
        InfoRow("Last PHY result", BleLongRangePolicy.statusName(status), WarnColor)
    }
    if (connection.outgoing) {
        InfoRow("Found on", link.discoveredPhy?.let(BleLongRangePolicy::phyName)?.let { "$it advertisement" } ?: "manual connect")
    }
    InfoRow(
        "Signal",
        link.rssi?.let { "$it dBm • ${signalLabel(it)}" } ?: "not measured (incoming side)",
        link.rssi?.let(::signalColor)
    )
    val total = connection.sendOk + connection.sendFailed
    InfoRow(
        "Delivery",
        "sent ${connection.sendOk}/$total" +
            (if (connection.sendFailed > 0) " (${connection.sendFailed} failed)" else "") +
            " • received ${connection.received}",
        if (connection.sendFailed > 0) WarnColor else null
    )
    InfoRow("Connected for", ageText(now - link.connectedAt))
}

@Composable
private fun SubHeader(text: String) {
    Text(
        text,
        fontFamily = BitchatFontFamily,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun Hint(text: String, color: Color? = null) {
    Text(
        text,
        fontFamily = BitchatFontFamily,
        fontSize = 11.sp,
        color = color ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    )
}

@Composable
private fun InfoRow(label: String, value: String, valueColor: Color? = null) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            fontFamily = BitchatFontFamily,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.width(150.dp)
        )
        Text(value, fontFamily = BitchatFontFamily, fontSize = 11.sp, color = valueColor ?: MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun CapabilityRow(label: String, supported: Boolean) {
    InfoRow(label, if (supported) "✓ yes" else "✗ no", if (supported) OkColor else WarnColor)
}

@Composable
private fun ActionText(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text,
        fontFamily = BitchatFontFamily,
        fontSize = 12.sp,
        color = if (enabled) OkColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
        modifier = if (enabled) Modifier.clickable(onClick = onClick) else Modifier
    )
}

private fun modeLabel(mode: BleLongRangeMode) = when (mode) {
    BleLongRangeMode.OFF -> "Off"
    BleLongRangeMode.ADAPTIVE -> "Adaptive"
    BleLongRangeMode.MAX_RANGE -> "Max range"
}

private fun phyText(phy: Int) = when (phy) {
    0 -> "unknown"
    BluetoothDevice.PHY_LE_CODED -> "Coded"
    else -> BleLongRangePolicy.phyName(phy)
}

private fun advertiserText(state: AdvertiserState, error: Int?): String = when (state) {
    AdvertiserState.ACTIVE -> "active"
    AdvertiserState.STARTING -> "starting…"
    AdvertiserState.STOPPED -> "stopped"
    AdvertiserState.DISABLED -> "off (mode Off)"
    AdvertiserState.UNSUPPORTED -> "unsupported" + (error?.let { " (error $it)" } ?: "")
    AdvertiserState.FAILED -> "failed" + (error?.let { " (error $it)" } ?: "")
}

private fun advertiserColor(state: AdvertiserState): Color? = when (state) {
    AdvertiserState.ACTIVE -> OkColor
    AdvertiserState.FAILED -> ErrorColor
    AdvertiserState.UNSUPPORTED -> WarnColor
    else -> null
}

private fun signalLabel(rssi: Int) = when {
    rssi >= -60 -> "strong"
    rssi >= -75 -> "good"
    rssi >= -85 -> "fair"
    rssi >= -95 -> "weak (1M edge)"
    else -> "Coded-only territory"
}

private fun signalColor(rssi: Int): Color = when {
    rssi >= -75 -> OkColor
    rssi >= -95 -> WarnColor
    else -> CodedColor
}

private fun ageText(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m ${s % 60}s"
        else -> "${s / 3600}h ${(s % 3600) / 60}m"
    }
}
