package com.bitchat.android.ui.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.rotate
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.services.meshgraph.MeshGraphService
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.stringResource
import com.bitchat.android.R
import androidx.compose.ui.platform.LocalContext
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.bitchat.android.onboarding.PermissionManager
import com.bitchat.android.core.ui.component.sheet.BitchatBottomSheet
import com.bitchat.android.core.ui.component.sheet.BitchatSheetTopBar
import com.bitchat.android.core.ui.component.sheet.BitchatSheetTitle
import com.bitchat.android.util.DistributionInfoProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MeshTopologySection(
    localPeerID: String? = null,
    blePeerIDs: Set<String> = emptySet(),
) {
    val colorScheme = MaterialTheme.colorScheme
    val graphService = remember { MeshGraphService.getInstance() }
    val snapshot by graphService.graphState.collectAsState()
    val wifiAwareConnected by com.bitchat.android.wifiaware.WifiAwareController.connectedPeers.collectAsState()
    val wifiAwarePeerIDs = remember(wifiAwareConnected) { wifiAwareConnected.keys.toSet() }

    Surface(shape = RoundedCornerShape(12.dp), color = colorScheme.surfaceVariant.copy(alpha = 0.2f)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.SettingsEthernet, contentDescription = null, tint = Color(0xFF8E8E93))
                Text("Mesh topology", fontFamily = BitchatFontFamily, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            val nodes = snapshot.nodes
            val edges = snapshot.edges
            val empty = nodes.isEmpty()
            if (empty) {
                Text("No gossip yet", fontFamily = BitchatFontFamily, fontSize = 11.sp, color = colorScheme.onSurface.copy(alpha = 0.6f))
            } else {
                ForceDirectedMeshGraph(
                    nodes = nodes,
                    edges = edges,
                    wifiAwarePeerIDs = wifiAwarePeerIDs,
                    blePeerIDs = blePeerIDs,
                    localPeerID = localPeerID,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .background(colorScheme.surface.copy(alpha = 0.4f))
                )
                
                // Flexible peer list
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    nodes.forEach { node ->
                        val label = "${node.peerID.take(8)} • ${node.nickname ?: "Unknown"}"
                        Text(
                            text = label,
                            fontFamily = BitchatFontFamily,
                            fontSize = 11.sp,
                            color = colorScheme.onSurface.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DistributionInfoSection(info: DistributionInfoProvider.DistributionInfo?) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colorScheme.surfaceVariant.copy(alpha = 0.2f)
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Filled.Devices, contentDescription = null, tint = Color(0xFF5856D6))
                Text(
                    "Distribution info",
                    fontFamily = BitchatFontFamily,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            if (info == null) {
                Text(
                    "Inspecting installed package…",
                    fontFamily = BitchatFontFamily,
                    fontSize = 11.sp,
                    color = colorScheme.onSurface.copy(alpha = 0.6f)
                )
            } else {
                DistributionInfoRow("Install source", info.installSource)
                info.installerPackage?.let {
                    DistributionInfoRow("Installer package", it)
                }
                DistributionInfoRow("Package format", info.packageFormat)
                DistributionInfoRow("APK architecture", info.architecture)
                DistributionInfoRow("Sharing source", info.sharingSource)
                DistributionInfoRow("Version", "${info.versionName} (${info.versionCode})")
                DistributionInfoRow("Signing channel", info.signingChannel)
                DistributionInfoRow(
                    label = "Certificate SHA-256",
                    value = info.certificateSha256 ?: "Unavailable"
                )

                if (info.certificateSha256 != null) {
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard?.setPrimaryClip(
                                ClipData.newPlainText(
                                    "BitChat signing certificate SHA-256",
                                    info.certificateSha256
                                )
                            )
                            Toast.makeText(context, "Certificate fingerprint copied", Toast.LENGTH_SHORT).show()
                        },
                        contentPadding = PaddingValues(horizontal = 0.dp)
                    ) {
                        Text("Copy certificate fingerprint", fontFamily = BitchatFontFamily)
                    }
                }
            }
        }
    }
}

@Composable
private fun DistributionInfoRow(label: String, value: String) {
    val colorScheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            fontFamily = BitchatFontFamily,
            fontSize = 10.sp,
            color = colorScheme.onSurface.copy(alpha = 0.55f)
        )
        Text(
            value,
            fontFamily = BitchatFontFamily,
            fontSize = 11.sp,
            color = colorScheme.onSurface.copy(alpha = 0.9f)
        )
    }
}

private enum class GraphMode { OVERALL, PER_DEVICE, PER_PEER }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DebugSettingsSheet(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    meshService: BluetoothMeshService
) {
    val colorScheme = MaterialTheme.colorScheme
    val manager = remember { DebugSettingsManager.getInstance() }

    val verboseLogging by manager.verboseLoggingEnabled.collectAsState()
    val gattServerEnabled by manager.gattServerEnabled.collectAsState()
    val gattClientEnabled by manager.gattClientEnabled.collectAsState()
    val packetRelayed by manager.packetRelayEnabled.collectAsState()
    val maxOverall by manager.maxConnectionsOverall.collectAsState()
    val maxServer by manager.maxServerConnections.collectAsState()
    val maxClient by manager.maxClientConnections.collectAsState()
    val debugMessages by manager.debugMessages.collectAsState()
    val scanResults by manager.scanResults.collectAsState()
    val connectedDevices by manager.connectedDevices.collectAsState()
    val relayStats by manager.relayStats.collectAsState()
    val seenCapacity by manager.seenPacketCapacity.collectAsState()
    val gcsMaxBytes by manager.gcsMaxBytes.collectAsState()
    val gcsFpr by manager.gcsFprPercent.collectAsState()
    val context = LocalContext.current
    var distributionInfo by remember {
        mutableStateOf<DistributionInfoProvider.DistributionInfo?>(null)
    }

    val bleEnabled by manager.bleEnabled.collectAsState()
    val wifiAwareEnabled by manager.wifiAwareEnabled.collectAsState()
    val wifiAwareVerbose by manager.wifiAwareVerbose.collectAsState()

    // Onboarding only asks for these when the toggle is already on, and it defaults to off,
    // so enabling from here has to request them or the controller never starts.
    val wifiAwarePermissions = remember { PermissionManager(context).wifiAwarePermissions() }
    val wifiAwarePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Check live state, not the result map — already-held permissions are filtered out
        // before launching. Only NEARBY_WIFI_DEVICES blocks startup; the other is defensive.
        val nearbyPermission = android.Manifest.permission.NEARBY_WIFI_DEVICES
        val nearbyGranted = nearbyPermission !in wifiAwarePermissions ||
            ContextCompat.checkSelfPermission(context, nearbyPermission) ==
                PackageManager.PERMISSION_GRANTED
        if (nearbyGranted) {
            manager.setWifiAwareEnabled(true)
        } else {
            manager.addDebugMessage(
                DebugMessage.SystemMessage("Wi‑Fi Aware needs the Nearby devices permission")
            )
        }
    }
    val enableWifiAware: () -> Unit = {
        val missing = wifiAwarePermissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            manager.setWifiAwareEnabled(true)
        } else {
            wifiAwarePermissionLauncher.launch(missing.toTypedArray())
        }
    }
    val wifiAwareDiscovered by manager.wifiAwareDiscovered.collectAsState()
    val wifiAwareConnected by manager.wifiAwareConnected.collectAsState()
    val wifiAwareSupported by com.bitchat.android.wifiaware.WifiAwareController.supported.collectAsState()
    val wifiAwareAvailable by com.bitchat.android.wifiaware.WifiAwareController.available.collectAsState()
    val wifiAwareSupportStatus by com.bitchat.android.wifiaware.WifiAwareController.supportStatus.collectAsState()
    // Persistent notification is now controlled solely by MeshServicePreferences.isBackgroundEnabled
    val listState = rememberLazyListState()
    val isScrolled by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
        }
    }
    val topBarAlpha by animateFloatAsState(
        targetValue = if (isScrolled) 0.95f else 0f,
        label = "topBarAlpha"
    )

    // Push live connected devices from mesh service whenever sheet is visible
    LaunchedEffect(isPresented) {
        if (isPresented) {
            // Poll device list periodically for now (TODO: add callbacks)
            while (true) {
                val entries = meshService.connectionManager.getConnectedDeviceEntries()
                val mapping = meshService.getDeviceAddressToPeerMapping()
                val peers = mapping.values.toSet()
                val nicknames = meshService.getPeerNicknames()
                val directMap = peers.associateWith { pid -> meshService.getPeerInfo(pid)?.isDirectConnection == true }
                val devices = entries.map { (address, isClient, rssi) ->
                    val pid = mapping[address]
                    com.bitchat.android.ui.debug.ConnectedDevice(
                        deviceAddress = address,
                        peerID = pid,
                        nickname = pid?.let { nicknames[it] },
                        rssi = rssi,
                        connectionType = if (isClient) ConnectionType.GATT_CLIENT else ConnectionType.GATT_SERVER,
                        isDirectConnection = pid?.let { directMap[it] } ?: false
                    )
                }
                manager.updateConnectedDevices(devices)
                // Also surface Wi‑Fi Aware status
                try {
                    val ctrl = com.bitchat.android.wifiaware.WifiAwareController
                    val known = ctrl.knownPeers.value
                    val discovered = ctrl.discoveredPeers.value
                    val discoveredMap = discovered.associateWith { pid -> known[pid] ?: "" }
                    manager.updateWifiAwareDiscovered(discoveredMap)
                    manager.updateWifiAwareConnected(ctrl.connectedPeers.value)
                } catch (_: Exception) { }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    LaunchedEffect(isPresented) {
        if (isPresented) {
            distributionInfo = withContext(Dispatchers.IO) {
                runCatching { DistributionInfoProvider.inspect(context) }.getOrNull()
            }
        }
    }

    val scope = rememberCoroutineScope()

    if (!isPresented) return

    BitchatBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        // Mark debug sheet visible/invisible to gate heavy work
        LaunchedEffect(Unit) { DebugSettingsManager.getInstance().setDebugSheetVisible(true) }
        DisposableEffect(Unit) {
            onDispose { DebugSettingsManager.getInstance().setDebugSheetVisible(false) }
        }
        Box(modifier = Modifier.fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 80.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Text(
                        text = stringResource(R.string.debug_tools_desc),
                        fontFamily = BitchatFontFamily,
                        fontSize = 12.sp,
                        color = colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            item {
                DistributionInfoSection(distributionInfo)
            }
            // Verbose logging toggle
            item {
                Surface(shape = RoundedCornerShape(12.dp), color = colorScheme.surfaceVariant.copy(alpha = 0.2f)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Filled.SettingsEthernet, contentDescription = null, tint = Color(0xFF00C851))
                            Text(stringResource(R.string.debug_verbose_logging), fontFamily = BitchatFontFamily, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.weight(1f))
                            Switch(checked = verboseLogging, onCheckedChange = { manager.setVerboseLoggingEnabled(it) })
                        }
                        Text(
                            stringResource(R.string.debug_verbose_hint),
                            fontFamily = BitchatFontFamily,
                            fontSize = 11.sp,
                            color = colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // Mesh topology visualization (moved below verbose logging)
            item {
                val blePeerIDs = remember(connectedDevices) {
                    connectedDevices.mapNotNull { it.peerID }.toSet()
                }
                MeshTopologySection(
                    localPeerID = meshService.myPeerID,
                    blePeerIDs = blePeerIDs,
                )
            }

            // GATT controls
            item {
                Surface(shape = RoundedCornerShape(12.dp), color = colorScheme.surfaceVariant.copy(alpha = 0.2f)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Filled.Bluetooth, contentDescription = null, tint = Color(0xFF007AFF))
                            Text(stringResource(R.string.debug_bluetooth_roles), fontFamily = BitchatFontFamily, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.debug_gatt_server), fontFamily = BitchatFontFamily, modifier = Modifier.weight(1f))
                            Switch(checked = gattServerEnabled, onCheckedChange = {
                                manager.setGattServerEnabled(it)
                                scope.launch {
                                    if (it) meshService.connectionManager.startServer() else meshService.connectionManager.stopServer()
                                }
                            })
                        }
                        val serverCount = connectedDevices.count { it.connectionType == ConnectionType.GATT_SERVER }
                        Text(stringResource(R.string.debug_connections_fmt, serverCount, maxServer), fontFamily = BitchatFontFamily, fontSize = 11.sp, color = colorScheme.onSurface.copy(alpha = 0.7f))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.debug_max_server), fontFamily = BitchatFontFamily, modifier = Modifier.width(90.dp))
                            Slider(
                                value = maxServer.toFloat(),
                                onValueChange = { manager.setMaxServerConnections(it.toInt().coerceAtLeast(1)) },
                                valueRange = 1f..32f,
                                steps = 30
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.debug_gatt_client), fontFamily = BitchatFontFamily, modifier = Modifier.weight(1f))
                            Switch(checked = gattClientEnabled, onCheckedChange = {
                                manager.setGattClientEnabled(it)
                                scope.launch {
                                    if (it) meshService.connectionManager.startClient() else meshService.connectionManager.stopClient()
                                }
                            })
                        }
                        val clientCount = connectedDevices.count { it.connectionType == ConnectionType.GATT_CLIENT }
                        Text(stringResource(R.string.debug_connections_fmt, clientCount, maxClient), fontFamily = BitchatFontFamily, fontSize = 11.sp, color = colorScheme.onSurface.copy(alpha = 0.7f))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.debug_max_client), fontFamily = BitchatFontFamily, modifier = Modifier.width(90.dp))
                            Slider(
                                value = maxClient.toFloat(),
                                onValueChange = { manager.setMaxClientConnections(it.toInt().coerceAtLeast(1)) },
                                valueRange = 1f..32f,
                                steps = 30
                            )
                        }
                        val overallCount = connectedDevices.size
                        Text(stringResource(R.string.debug_overall_connections_fmt, overallCount, maxOverall), fontFamily = BitchatFontFamily, fontSize = 11.sp, color = colorScheme.onSurface.copy(alpha = 0.7f))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.debug_max_overall), fontFamily = BitchatFontFamily, modifier = Modifier.width(90.dp))
                            Slider(
                                value = maxOverall.toFloat(),
                                onValueChange = { manager.setMaxConnectionsOverall(it.toInt().coerceAtLeast(1)) },
                                valueRange = 1f..32f,
                                steps = 30
                            )
                        }
                        Text(
                            stringResource(R.string.debug_roles_hint),
                            fontFamily = BitchatFontFamily,
                            fontSize = 11.sp,
                            color = colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // Transport toggles (BLE + Wi‑Fi Aware)
            item {
                Surface(shape = RoundedCornerShape(12.dp), color = colorScheme.surfaceVariant.copy(alpha = 0.2f)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Filled.Devices, contentDescription = null, tint = Color(0xFF4CAF50))
                            Text("Transports", fontFamily = BitchatFontFamily, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Bluetooth, contentDescription = null, tint = Color(0xFF007AFF))
                            Spacer(Modifier.width(8.dp))
                            Text("BLE", fontFamily = BitchatFontFamily, modifier = Modifier.weight(1f))
                            Switch(checked = bleEnabled, onCheckedChange = {
                                manager.setBleEnabled(it)
                            })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Wifi, contentDescription = null, tint = Color(0xFF9C27B0))
                            Spacer(Modifier.width(8.dp))
                            Text("Wi‑Fi Aware", fontFamily = BitchatFontFamily, modifier = Modifier.weight(1f))
                            val wifiSwitchEnabled = wifiAwareSupported
                            Text(
                                when {
                                    !wifiAwareSupported -> "unsupported"
                                    wifiAwareAvailable -> "available"
                                    else -> "unavailable"
                                },
                                fontFamily = BitchatFontFamily,
                                fontSize = 11.sp,
                                color = colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = wifiAwareEnabled && wifiAwareSupported,
                                enabled = wifiSwitchEnabled,
                                onCheckedChange = { on ->
                                    if (on) enableWifiAware() else manager.setWifiAwareEnabled(false)
                                }
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.width(24.dp))
                            Text("Wi‑Fi Aware verbose logging", fontFamily = BitchatFontFamily, modifier = Modifier.weight(1f))
                            Switch(checked = wifiAwareVerbose, onCheckedChange = { manager.setWifiAwareVerbose(it) })
                        }
                    }
                }
            }

            // Packet relay controls and stats
            item {
                Surface(shape = RoundedCornerShape(12.dp), color = colorScheme.surfaceVariant.copy(alpha = 0.2f)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Persistent notification is controlled by About sheet (MeshServicePreferences.isBackgroundEnabled)

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Filled.PowerSettingsNew, contentDescription = null, tint = Color(0xFFFF9500))
                            Text(stringResource(R.string.debug_packet_relay), fontFamily = BitchatFontFamily, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.weight(1f))
                            Switch(checked = packetRelayed, onCheckedChange = { manager.setPacketRelayEnabled(it) })
                        }
                        // Removed aggregate labels; we will show per-direction compact labels below titles
                        // Toggle: overall vs per-connection vs per-peer
                        var graphMode by rememberSaveable { mutableStateOf(GraphMode.OVERALL) }
                        val perDeviceIncoming by manager.perDeviceIncomingLastSecond.collectAsState()
                        val perPeerIncoming by manager.perPeerIncomingLastSecond.collectAsState()
                        val perDeviceOutgoing by manager.perDeviceOutgoingLastSecond.collectAsState()
                        val perPeerOutgoing by manager.perPeerOutgoingLastSecond.collectAsState()
                        val perDeviceIncoming1m by manager.perDeviceIncomingLastMinute.collectAsState()
                        val perDeviceOutgoing1m by manager.perDeviceOutgoingLastMinute.collectAsState()
                        val perPeerIncoming1m by manager.perPeerIncomingLastMinute.collectAsState()
                        val perPeerOutgoing1m by manager.perPeerOutgoingLastMinute.collectAsState()
                        val perDeviceIncomingTotal by manager.perDeviceIncomingTotal.collectAsState()
                        val perDeviceOutgoingTotal by manager.perDeviceOutgoingTotal.collectAsState()
                        val perPeerIncomingTotal by manager.perPeerIncomingTotal.collectAsState()
                        val perPeerOutgoingTotal by manager.perPeerOutgoingTotal.collectAsState()
                        val nicknameMap = remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
                        val devicePeerMap = remember { mutableStateOf<Map<String, String>>(emptyMap()) }
                        LaunchedEffect(Unit) {
                            try { nicknameMap.value = meshService.getPeerNicknames() } catch (_: Exception) { }
                            // Try to fetch device->peer map periodically for legend resolution
                            while (isPresented) {
                                try { devicePeerMap.value = meshService.getDeviceAddressToPeerMapping() } catch (_: Exception) { }
                                kotlinx.coroutines.delay(1000)
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Mode selector
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = graphMode == GraphMode.OVERALL,
                                    onClick = { graphMode = GraphMode.OVERALL },
                                    label = { Text("Overall") }
                                )
                                FilterChip(
                                    selected = graphMode == GraphMode.PER_DEVICE,
                                    onClick = { graphMode = GraphMode.PER_DEVICE },
                                    label = { Text("Per Device") },
                                    leadingIcon = { Icon(Icons.Filled.Devices, contentDescription = null) }
                                )
                                FilterChip(
                                    selected = graphMode == GraphMode.PER_PEER,
                                    onClick = { graphMode = GraphMode.PER_PEER },
                                    label = { Text("Per Peer") },
                                    leadingIcon = { Icon(Icons.Filled.SettingsEthernet, contentDescription = null) }
                                )
                            }

                            // Time series state
                            var overallSeriesIncoming by rememberSaveable { mutableStateOf(List(60) { 0f }) }
                            var overallSeriesOutgoing by rememberSaveable { mutableStateOf(List(60) { 0f }) }
                            var stackedKeysIncoming by rememberSaveable { mutableStateOf(listOf<String>()) }
                            var stackedKeysOutgoing by rememberSaveable { mutableStateOf(listOf<String>()) }
                            var stackedSeriesIncoming by rememberSaveable { mutableStateOf<Map<String, List<Float>>>(emptyMap()) }
                            var stackedSeriesOutgoing by rememberSaveable { mutableStateOf<Map<String, List<Float>>>(emptyMap()) }
                            var highlightedKey by rememberSaveable { mutableStateOf<String?>(null) }

                            // Color palette for stacked legend
                            val palette = remember {
                                listOf(
                                    Color(0xFF00C851), Color(0xFF007AFF), Color(0xFFFF9500), Color(0xFFFF3B30),
                                    Color(0xFF5AC8FA), Color(0xFFAF52DE), Color(0xFFFF2D55), Color(0xFF34C759),
                                    Color(0xFFFFCC00), Color(0xFF5856D6)
                                )
                            }
                            val colorForKey = remember { mutableStateMapOf<String, Color>() }
                            fun stableColorFor(key: String): Color {
                                // Deterministic fallback color based on key hash using HSV palette
                                val h = (key.hashCode().toUInt().toInt() and 0x7FFFFFFF) % 360
                                return Color.hsv(h.toFloat(), 0.65f, 0.95f)
                            }
                            // Ensure colors are assigned for current keys before drawing
                            fun ensureColors(keys: List<String>) {
                                keys.forEachIndexed { idx, k ->
                                    colorForKey.putIfAbsent(k, palette.getOrNull(idx) ?: stableColorFor(k))
                                }
                            }

                            LaunchedEffect(isPresented, graphMode) {
                                while (isPresented) {
                                    when (graphMode) {
                                        GraphMode.OVERALL -> {
                                            val sIn = relayStats.lastSecondIncoming.toFloat()
                                            val sOut = relayStats.lastSecondOutgoing.toFloat()
                                            overallSeriesIncoming = (overallSeriesIncoming + sIn).takeLast(60)
                                            overallSeriesOutgoing = (overallSeriesOutgoing + sOut).takeLast(60)
                                        }
                                        GraphMode.PER_DEVICE -> {
                                            val snapshotIn = perDeviceIncoming
                                            val snapshotOut = perDeviceOutgoing
                                            fun advance(base: Map<String, List<Float>>, snap: Map<String, Int>): Map<String, List<Float>> {
                                                val next = mutableMapOf<String, List<Float>>()
                                                val union = (base.keys + snap.keys).toSet()
                                                union.forEach { k ->
                                                    val prev = base[k] ?: List(60) { 0f }
                                                    val s = (snap[k] ?: 0).toFloat()
                                                    next[k] = (prev + s).takeLast(60)
                                                }
                                                return next
                                            }
                                            // Advance and prune fully-stale series (all-zero in visible window)
                                            stackedSeriesIncoming = advance(stackedSeriesIncoming, snapshotIn).filterValues { series -> series.any { it != 0f } }
                                            stackedSeriesOutgoing = advance(stackedSeriesOutgoing, snapshotOut).filterValues { series -> series.any { it != 0f } }
                                            stackedKeysIncoming = stackedSeriesIncoming.keys.sorted()
                                            stackedKeysOutgoing = stackedSeriesOutgoing.keys.sorted()
                                        }
                                        GraphMode.PER_PEER -> {
                                            val snapshotIn = perPeerIncoming
                                            val snapshotOut = perPeerOutgoing
                                            fun advance(base: Map<String, List<Float>>, snap: Map<String, Int>): Map<String, List<Float>> {
                                                val next = mutableMapOf<String, List<Float>>()
                                                val union = (base.keys + snap.keys).toSet()
                                                union.forEach { k ->
                                                    val prev = base[k] ?: List(60) { 0f }
                                                    val s = (snap[k] ?: 0).toFloat()
                                                    next[k] = (prev + s).takeLast(60)
                                                }
                                                return next
                                            }
                                            stackedSeriesIncoming = advance(stackedSeriesIncoming, snapshotIn).filterValues { series -> series.any { it != 0f } }
                                            stackedSeriesOutgoing = advance(stackedSeriesOutgoing, snapshotOut).filterValues { series -> series.any { it != 0f } }
                                            stackedKeysIncoming = stackedSeriesIncoming.keys.sorted()
                                            stackedKeysOutgoing = stackedSeriesOutgoing.keys.sorted()
                                        }
                                    }
                                    kotlinx.coroutines.delay(1000)
                                }
                            }
                            
                            // Helper functions moved to top-level composable below to avoid scope issues

                            // Render two blocks: Incoming and Outgoing
                            Text("Incoming", fontFamily = BitchatFontFamily, fontSize = 11.sp, color = colorScheme.onSurface.copy(alpha = 0.7f))
                            Text(
                                "${relayStats.lastSecondIncoming}/s • ${relayStats.lastMinuteIncoming}/m • ${relayStats.last15MinuteIncoming}/15m • total ${relayStats.totalIncomingCount}",
                                fontFamily = BitchatFontFamily, fontSize = 10.sp, color = colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                            DrawGraphBlock(
                                title = "Incoming",
                                stackedKeys = stackedKeysIncoming,
                                stackedSeries = stackedSeriesIncoming,
                                overallSeries = if (graphMode == GraphMode.OVERALL) overallSeriesIncoming else null,
                                graphMode = graphMode,
                                highlightedKey = highlightedKey,
                                onToggleHighlight = { key -> highlightedKey = if (highlightedKey == key) null else key },
                                ensureColors = { keys -> ensureColors(keys) },
                                colorForKey = { k -> colorForKey[k] ?: stableColorFor(k) },
                                legendTitleFor = { key ->
                                    when (graphMode) {
                                        GraphMode.PER_PEER -> {
                                            val nick = nicknameMap.value[key]
                                            val prefix = key.take(6)
                                            if (!nick.isNullOrBlank()) "$nick ($prefix)" else prefix
                                        }
                                        GraphMode.PER_DEVICE -> {
                                            val device = key
                                            val pid = connectedDevices.firstOrNull { it.deviceAddress == device }?.peerID
                                                ?: devicePeerMap.value[device]
                                            if (pid != null) {
                                                val nick = nicknameMap.value[pid]
                                                val prefix = pid.take(6)
                                                "$device (${if (!nick.isNullOrBlank()) "$nick ($prefix)" else prefix})"
                                            } else device
                                        }
                                        else -> key
                                    }
                                },
                                legendMetricsFor = { key ->
                                    when (graphMode) {
                                        GraphMode.PER_PEER -> {
                                            val s = perPeerIncoming[key] ?: 0
                                            val m = perPeerIncoming1m[key] ?: 0
                                            val t = (perPeerIncomingTotal[key] ?: 0L)
                                            "${s}/s • ${m}/m • total ${t}"
                                        }
                                        GraphMode.PER_DEVICE -> {
                                            val s = perDeviceIncoming[key] ?: 0
                                            val m = perDeviceIncoming1m[key] ?: 0
                                            val t = (perDeviceIncomingTotal[key] ?: 0L)
                                            "${s}/s • ${m}/m • total ${t}"
                                        }
                                        else -> ""
                                    }
                                }
                            )
                            if (graphMode != GraphMode.OVERALL && stackedKeysIncoming.isNotEmpty()) { /* legend printed inside DrawGraphBlock */ }

                            Spacer(Modifier.height(8.dp))
                            Text("Outgoing", fontFamily = BitchatFontFamily, fontSize = 11.sp, color = colorScheme.onSurface.copy(alpha = 0.7f))
                            Text(
                                "${relayStats.lastSecondOutgoing}/s • ${relayStats.lastMinuteOutgoing}/m • ${relayStats.last15MinuteOutgoing}/15m • total ${relayStats.totalOutgoingCount}",
                                fontFamily = BitchatFontFamily, fontSize = 10.sp, color = colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                            DrawGraphBlock(
                                title = "Outgoing",
                                stackedKeys = stackedKeysOutgoing,
                                stackedSeries = stackedSeriesOutgoing,
                                overallSeries = if (graphMode == GraphMode.OVERALL) overallSeriesOutgoing else null,
                                graphMode = graphMode,
                                highlightedKey = highlightedKey,
                                onToggleHighlight = { key -> highlightedKey = if (highlightedKey == key) null else key },
                                ensureColors = { keys -> ensureColors(keys) },
                                colorForKey = { k -> colorForKey[k] ?: stableColorFor(k) },
                                legendTitleFor = { key ->
                                    when (graphMode) {
                                        GraphMode.PER_PEER -> {
                                            val nick = nicknameMap.value[key]
                                            val prefix = key.take(6)
                                            if (!nick.isNullOrBlank()) "$nick ($prefix)" else prefix
                                        }
                                        GraphMode.PER_DEVICE -> {
                                            val device = key
                                            val pid = connectedDevices.firstOrNull { it.deviceAddress == device }?.peerID
                                                ?: devicePeerMap.value[device]
                                            if (pid != null) {
                                                val nick = nicknameMap.value[pid]
                                                val prefix = pid.take(6)
                                                "$device (${if (!nick.isNullOrBlank()) "$nick ($prefix)" else prefix})"
                                            } else device
                                        }
                                        else -> key
                                    }
                                },
                                legendMetricsFor = { key ->
                                    when (graphMode) {
                                        GraphMode.PER_PEER -> {
                                            val s = perPeerOutgoing[key] ?: 0
                                            val m = perPeerOutgoing1m[key] ?: 0
                                            val t = (perPeerOutgoingTotal[key] ?: 0L)
                                            "${s}/s • ${m}/m • total ${t}"
                                        }
                                        GraphMode.PER_DEVICE -> {
                                            val s = perDeviceOutgoing[key] ?: 0
                                            val m = perDeviceOutgoing1m[key] ?: 0
                                            val t = (perDeviceOutgoingTotal[key] ?: 0L)
                                            "${s}/s • ${m}/m • total ${t}"
                                        }
                                        else -> ""
                                    }
                                }
                            )
                            if (graphMode != GraphMode.OVERALL && stackedKeysOutgoing.isNotEmpty()) { /* legend printed inside DrawGraphBlock */ }
                        }
                    }
                }
            }

            // Wi‑Fi Aware controls and status
            item {
                Surface(shape = RoundedCornerShape(12.dp), color = colorScheme.surfaceVariant.copy(alpha = 0.2f)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val running by com.bitchat.android.wifiaware.WifiAwareController.running.collectAsState()
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Filled.WifiTethering, contentDescription = null, tint = Color(0xFF9C27B0))
                            Text("Wi‑Fi Aware", fontFamily = BitchatFontFamily, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.weight(1f))
                            val wifiStatusText = when {
                                !wifiAwareSupported -> "unsupported"
                                running -> "running"
                                !wifiAwareAvailable -> "unavailable"
                                else -> "stopped"
                            }
                            Text(wifiStatusText, fontFamily = BitchatFontFamily, fontSize = 12.sp, color = colorScheme.onSurface.copy(alpha = 0.7f))
                        }
                        if (!wifiAwareSupported) {
                            Text(
                                wifiAwareSupportStatus?.reason ?: "Wi-Fi Aware is not supported on this device",
                                fontFamily = BitchatFontFamily,
                                fontSize = 11.sp,
                                color = colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AssistChip(
                                onClick = enableWifiAware,
                                enabled = wifiAwareSupported,
                                label = { Text("Start") }
                            )
                            AssistChip(onClick = { manager.setWifiAwareEnabled(false) }, label = { Text("Stop") })
                            AssistChip(
                                onClick = { com.bitchat.android.wifiaware.WifiAwareController.getService()?.sendBroadcastAnnounce() },
                                enabled = running,
                                label = { Text("Announce") }
                            )
                        }
                        Text("Discovered: ${wifiAwareDiscovered.size}", fontFamily = BitchatFontFamily, fontSize = 12.sp)
                        if (wifiAwareDiscovered.isEmpty()) {
                            Text("No discoveries yet", fontFamily = BitchatFontFamily, fontSize = 11.sp, color = colorScheme.onSurface.copy(alpha = 0.6f))
                        } else {
                            wifiAwareDiscovered.entries.take(50).forEach { (peer, nick) ->
                                Text("• ${if (nick.isBlank()) peer.take(8) + "…" else nick} (${peer.take(8)}…) ", fontFamily = BitchatFontFamily, fontSize = 12.sp)
                            }
                        }
                        Divider()
                        Text("Connected: ${wifiAwareConnected.size}", fontFamily = BitchatFontFamily, fontSize = 12.sp)
                        if (wifiAwareConnected.isEmpty()) {
                            Text("No active sockets", fontFamily = BitchatFontFamily, fontSize = 11.sp, color = colorScheme.onSurface.copy(alpha = 0.6f))
                        } else {
                            wifiAwareConnected.entries.take(50).forEach { (peer, ip) ->
                                Text("• ${peer.take(8)}… @ $ip", fontFamily = BitchatFontFamily, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // Connected devices
            item {
                Surface(shape = RoundedCornerShape(12.dp), color = colorScheme.surfaceVariant.copy(alpha = 0.2f)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Filled.SettingsEthernet, contentDescription = null, tint = Color(0xFF9C27B0))
                            Text("Sync settings", fontFamily = BitchatFontFamily, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                        Text(stringResource(R.string.debug_max_packets_per_sync_fmt, seenCapacity), fontFamily = BitchatFontFamily, fontSize = 11.sp, color = colorScheme.onSurface.copy(alpha = 0.7f))
                        Slider(value = seenCapacity.toFloat(), onValueChange = { manager.setSeenPacketCapacity(it.toInt()) }, valueRange = 10f..1000f, steps = 99)
                        Text(stringResource(R.string.debug_max_gcs_filter_size_fmt, gcsMaxBytes), fontFamily = BitchatFontFamily, fontSize = 11.sp, color = colorScheme.onSurface.copy(alpha = 0.7f))
                        Slider(value = gcsMaxBytes.toFloat(), onValueChange = { manager.setGcsMaxBytes(it.toInt()) }, valueRange = 128f..1024f, steps = 0)
                        Text(stringResource(R.string.debug_target_fpr_fmt, gcsFpr), fontFamily = BitchatFontFamily, fontSize = 11.sp, color = colorScheme.onSurface.copy(alpha = 0.7f))
                        Slider(value = gcsFpr.toFloat(), onValueChange = { manager.setGcsFprPercent(it.toDouble()) }, valueRange = 0.1f..5.0f, steps = 49)
                        val p = remember(gcsFpr) { com.bitchat.android.sync.GCSFilter.deriveP(gcsFpr / 100.0) }
                        val nmax = remember(gcsFpr, gcsMaxBytes) { com.bitchat.android.sync.GCSFilter.estimateMaxElementsForSize(gcsMaxBytes, p) }
                        Text(stringResource(R.string.debug_derived_p_fmt, p.toString(), nmax.toString()), fontFamily = BitchatFontFamily, fontSize = 11.sp, color = colorScheme.onSurface.copy(alpha = 0.7f))
                    }
                }
            }

            // Connected devices
            item {
                Surface(shape = RoundedCornerShape(12.dp), color = colorScheme.surfaceVariant.copy(alpha = 0.2f)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Filled.Devices, contentDescription = null, tint = Color(0xFF4CAF50))
                            Text(stringResource(R.string.debug_connected_devices), fontFamily = BitchatFontFamily, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                        val localAddr = remember { meshService.connectionManager.getLocalAdapterAddress() }
                        Text(stringResource(R.string.debug_our_device_id_fmt, localAddr ?: stringResource(R.string.unknown)), fontFamily = BitchatFontFamily, fontSize = 11.sp, color = colorScheme.onSurface.copy(alpha = 0.7f))
                        if (connectedDevices.isEmpty()) {
                            Text("None", fontFamily = BitchatFontFamily, fontSize = 11.sp, color = colorScheme.onSurface.copy(alpha = 0.6f))
                        } else {
                            connectedDevices.forEach { dev ->
                                Surface(shape = RoundedCornerShape(8.dp), color = colorScheme.surface.copy(alpha = 0.6f)) {
                                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text((dev.peerID ?: stringResource(R.string.unknown)) + " • ${dev.deviceAddress}", fontFamily = BitchatFontFamily, fontSize = 12.sp)
                                            val roleLabel = if (dev.connectionType == ConnectionType.GATT_SERVER) stringResource(R.string.debug_role_server) else stringResource(R.string.debug_role_client)
                                            Text("${dev.nickname ?: ""} • " + stringResource(R.string.debug_rssi_fmt, dev.rssi ?: stringResource(R.string.debug_question_mark)) + " • $roleLabel" + (if (dev.isDirectConnection) stringResource(R.string.debug_direct_suffix) else ""), fontFamily = BitchatFontFamily, fontSize = 11.sp, color = colorScheme.onSurface.copy(alpha = 0.7f))
                                        }
                                        Text(stringResource(R.string.debug_disconnect), color = Color(0xFFBF1A1A), fontFamily = BitchatFontFamily, modifier = Modifier.clickable {
                                            meshService.connectionManager.disconnectAddress(dev.deviceAddress)
                                        })
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Recent scan results
            item {
                Surface(shape = RoundedCornerShape(12.dp), color = colorScheme.surfaceVariant.copy(alpha = 0.2f)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Filled.Bluetooth, contentDescription = null, tint = Color(0xFF007AFF))
                            Text(stringResource(R.string.debug_recent_scan_results), fontFamily = BitchatFontFamily, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                        if (scanResults.isEmpty()) {
                            Text(stringResource(R.string.debug_none), fontFamily = BitchatFontFamily, fontSize = 11.sp, color = colorScheme.onSurface.copy(alpha = 0.6f))
                        } else {
                            scanResults.forEach { res ->
                                Surface(shape = RoundedCornerShape(8.dp), color = colorScheme.surface.copy(alpha = 0.6f)) {
                                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text((res.peerID ?: stringResource(R.string.unknown)) + " • ${res.deviceAddress}", fontFamily = BitchatFontFamily, fontSize = 12.sp)
                                            Text(stringResource(R.string.debug_rssi_fmt, res.rssi.toString()), fontFamily = BitchatFontFamily, fontSize = 11.sp, color = colorScheme.onSurface.copy(alpha = 0.7f))
                                        }
                                        Text(stringResource(R.string.debug_connect), color = Color(0xFF00C851), fontFamily = BitchatFontFamily, modifier = Modifier.clickable {
                                            meshService.connectionManager.connectToAddress(res.deviceAddress)
                                        })
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Debug console
            item {
                Surface(shape = RoundedCornerShape(12.dp), color = colorScheme.surfaceVariant.copy(alpha = 0.2f)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Filled.BugReport, contentDescription = null, tint = Color(0xFFFF9500))
                            Text(stringResource(R.string.debug_debug_console), fontFamily = BitchatFontFamily, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.weight(1f))
                            Text(stringResource(R.string.debug_clear), color = Color(0xFFBF1A1A), fontFamily = BitchatFontFamily, modifier = Modifier.clickable {
                                manager.clearDebugMessages()
                            })
                        }
                        Column(Modifier.heightIn(max = 260.dp).background(colorScheme.surface.copy(alpha = 0.5f)).padding(8.dp)) {
                            debugMessages.takeLast(100).reversed().forEach { msg ->
                                Text("${msg.content}", fontFamily = BitchatFontFamily, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

                item { Spacer(Modifier.height(16.dp)) }
            }

            BitchatSheetTopBar(
                onClose = onDismiss,
                modifier = Modifier.align(Alignment.TopCenter),
                backgroundAlpha = topBarAlpha,
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.BugReport,
                            contentDescription = null,
                            tint = Color(0xFFFF9500)
                        )
                        BitchatSheetTitle(
                            text = stringResource(R.string.debug_tools)
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun DrawGraphBlock(
    title: String,
    stackedKeys: List<String>,
    stackedSeries: Map<String, List<Float>>,
    overallSeries: List<Float>?,
    graphMode: GraphMode,
    highlightedKey: String?,
    onToggleHighlight: (String) -> Unit,
    ensureColors: (List<String>) -> Unit,
    colorForKey: (String) -> Color,
    legendTitleFor: (String) -> String,
    legendMetricsFor: (String) -> String
) {
    val colorScheme = MaterialTheme.colorScheme
    val leftGutter = 40.dp
    Box(Modifier.fillMaxWidth().height(56.dp)) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val axisPx = leftGutter.toPx()
            val barCount = 60
            val availW = (size.width - axisPx).coerceAtLeast(1f)
            val w = availW / barCount
            val h = size.height
            drawLine(
                color = Color(0x33888888),
                start = androidx.compose.ui.geometry.Offset(axisPx, h - 1f),
                end = androidx.compose.ui.geometry.Offset(size.width, h - 1f),
                strokeWidth = 1f
            )

            when (graphMode) {
                GraphMode.OVERALL -> {
                    val maxValRaw = (overallSeries?.maxOrNull() ?: 0f)
                    val maxVal = if (maxValRaw > 0f) maxValRaw else 0f
                    (overallSeries ?: emptyList()).forEachIndexed { i, value ->
                        if (value > 0f && maxVal > 0f) {
                            val ratio = (value / maxVal).coerceIn(0f, 1f)
                            val barHeight = (h * ratio).coerceAtLeast(0f)
                            if (barHeight > 0.5f) {
                                drawRect(
                                    color = Color(0xFF00C851),
                                    topLeft = androidx.compose.ui.geometry.Offset(x = axisPx + i * w, y = h - barHeight),
                                    size = androidx.compose.ui.geometry.Size(w, barHeight)
                                )
                            }
                        }
                    }
                }
                else -> {
                    val indices = 0 until 60
                    val totals = indices.map { idx ->
                        stackedSeries.values.sumOf { it.getOrNull(idx)?.toDouble() ?: 0.0 }.toFloat()
                    }
                    val maxTotal = (totals.maxOrNull() ?: 0f)
                    val drawKeysBars = if (stackedKeys.isNotEmpty()) stackedKeys else stackedSeries.keys.sorted()
                    indices.forEach { i ->
                        var yTop = h
                        if (maxTotal > 0f) {
                            ensureColors(drawKeysBars)
                            drawKeysBars.forEach { k ->
                                val v = stackedSeries[k]?.getOrNull(i) ?: 0f
                                if (v > 0f) {
                                    val ratio = (v / maxTotal).coerceIn(0f, 1f)
                                    val segH = (h * ratio)
                                    if (segH > 0.5f) {
                                        val top = (yTop - segH)
                                        val baseColor = colorForKey(k)
                                        val c = if (highlightedKey == null || highlightedKey == k) baseColor else baseColor.copy(alpha = 0.35f)
                                        drawRect(
                                            color = c,
                                            topLeft = androidx.compose.ui.geometry.Offset(x = axisPx + i * w, y = top),
                                            size = androidx.compose.ui.geometry.Size(w, segH)
                                        )
                                        yTop = top
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(leftGutter).fillMaxHeight()) {
                Text(
                    "p/s",
                    fontFamily = BitchatFontFamily,
                    fontSize = 10.sp,
                    color = colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 2.dp).rotate(-90f)
                )
                val topLabel = when (graphMode) {
                    GraphMode.OVERALL -> (overallSeries?.maxOrNull() ?: 0f).toInt().toString()
                    else -> {
                        val totals = (0 until 60).map { idx -> stackedSeries.values.sumOf { it.getOrNull(idx)?.toDouble() ?: 0.0 }.toFloat() }
                        (totals.maxOrNull() ?: 0f).toInt().toString()
                    }
                }
                Text(
                    topLabel,
                    fontFamily = BitchatFontFamily,
                    fontSize = 10.sp,
                    color = colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = 4.dp)
                )
                Text(
                    "0",
                    fontFamily = BitchatFontFamily,
                    fontSize = 10.sp,
                    color = colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 4.dp)
                )
            }
            Spacer(Modifier.weight(1f))
        }
    }

    val drawKeys = if (stackedKeys.isNotEmpty()) stackedKeys else stackedSeries.keys.sorted()
    if (graphMode != GraphMode.OVERALL && drawKeys.isNotEmpty()) {
        Column(Modifier.fillMaxWidth()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                drawKeys.forEach { key ->
                    val baseColor = colorForKey(key)
                    val dimmed = highlightedKey != null && highlightedKey != key
                    val swatchColor = if (dimmed) baseColor.copy(alpha = 0.35f) else baseColor
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.clickable { onToggleHighlight(key) }
                    ) {
                        Box(Modifier.size(10.dp).background(swatchColor, RoundedCornerShape(2.dp)))
                        Column {
                            Text(legendTitleFor(key), fontFamily = BitchatFontFamily, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dimmed) 0.6f else 0.95f))
                            Text(legendMetricsFor(key), fontFamily = BitchatFontFamily, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dimmed) 0.45f else 0.75f))
                        }
                    }
                }
            }
        }
    }
}
