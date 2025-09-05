package com.bitchat.android.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.rotate
import com.bitchat.android.mesh.BluetoothMeshService
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugSettingsSheet(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    meshService: BluetoothMeshService
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val colorScheme = MaterialTheme.colorScheme
    val manager = remember { DebugSettingsManager.getInstance() }

    val verboseLogging by manager.verboseLoggingEnabled.collectAsState()
    val gattServerEnabled by manager.gattServerEnabled.collectAsState()
    val gattClientEnabled by manager.gattClientEnabled.collectAsState()
    val packetRelayEnabled by manager.packetRelayEnabled.collectAsState()
    val maxOverall by manager.maxConnectionsOverall.collectAsState()
    val maxServer by manager.maxServerConnections.collectAsState()
    val maxClient by manager.maxClientConnections.collectAsState()
    val debugMessages by manager.debugMessages.collectAsState()
    val scanResults by manager.scanResults.collectAsState()
    val connectedDevices by manager.connectedDevices.collectAsState()
    val relayStats by manager.relayStats.collectAsState()

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
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    val scope = rememberCoroutineScope()

    if (!isPresented) return

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.BugReport, contentDescription = null, tint = Color(0xFFFF9500))
                    Text("debug tools", fontFamily = FontFamily.Monospace, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                }
                Text(
                    text = "developer utilities for diagnostics and control",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }

            // Verbose logging toggle
            item {
                Surface(shape = RoundedCornerShape(12.dp), color = colorScheme.surfaceVariant.copy(alpha = 0.2f)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Filled.SettingsEthernet, contentDescription = null, tint = Color(0xFF00C851))
                            Text("verbose logging", fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.weight(1f))
                            Switch(checked = verboseLogging, onCheckedChange = { manager.setVerboseLoggingEnabled(it) })
                        }
                        Text(
                            "logs peer joins/leaves, connection direction, packet routing and relays",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // GATT controls
            item {
                Surface(shape = RoundedCornerShape(12.dp), color = colorScheme.surfaceVariant.copy(alpha = 0.2f)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Filled.Bluetooth, contentDescription = null, tint = Color(0xFF007AFF))
                            Text("bluetooth roles", fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("gatt server", fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                            Switch(checked = gattServerEnabled, onCheckedChange = {
                                manager.setGattServerEnabled(it)
                                scope.launch {
                                    if (it) meshService.connectionManager.startServer() else meshService.connectionManager.stopServer()
                                }
                            })
                        }
                        val serverCount = connectedDevices.count { it.connectionType == ConnectionType.GATT_SERVER }
                        Text("connections: $serverCount / $maxServer", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colorScheme.onSurface.copy(alpha = 0.7f))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("max server", fontFamily = FontFamily.Monospace, modifier = Modifier.width(90.dp))
                            Slider(
                                value = maxServer.toFloat(),
                                onValueChange = { manager.setMaxServerConnections(it.toInt().coerceAtLeast(1)) },
                                valueRange = 1f..32f,
                                steps = 30
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("gatt client", fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                            Switch(checked = gattClientEnabled, onCheckedChange = {
                                manager.setGattClientEnabled(it)
                                scope.launch {
                                    if (it) meshService.connectionManager.startClient() else meshService.connectionManager.stopClient()
                                }
                            })
                        }
                        val clientCount = connectedDevices.count { it.connectionType == ConnectionType.GATT_CLIENT }
                        Text("connections: $clientCount / $maxClient", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colorScheme.onSurface.copy(alpha = 0.7f))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("max client", fontFamily = FontFamily.Monospace, modifier = Modifier.width(90.dp))
                            Slider(
                                value = maxClient.toFloat(),
                                onValueChange = { manager.setMaxClientConnections(it.toInt().coerceAtLeast(1)) },
                                valueRange = 1f..32f,
                                steps = 30
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("max overall", fontFamily = FontFamily.Monospace, modifier = Modifier.width(90.dp))
                            Slider(
                                value = maxOverall.toFloat(),
                                onValueChange = { manager.setMaxConnectionsOverall(it.toInt().coerceAtLeast(1)) },
                                valueRange = 1f..32f,
                                steps = 30
                            )
                        }
                        Text(
                            "turn roles on/off and close all connections when disabled",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // Packet relay controls and stats
            item {
                Surface(shape = RoundedCornerShape(12.dp), color = colorScheme.surfaceVariant.copy(alpha = 0.2f)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Filled.PowerSettingsNew, contentDescription = null, tint = Color(0xFFFF9500))
                            Text("packet relay", fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.weight(1f))
                            Switch(checked = packetRelayEnabled, onCheckedChange = { manager.setPacketRelayEnabled(it) })
                        }
                        Text("since start: ${relayStats.totalRelaysCount}", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        Text("last 10s: ${relayStats.last10SecondRelays} • 1m: ${relayStats.lastMinuteRelays} • 15m: ${relayStats.last15MinuteRelays}", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        // Realtime graph: per-second relays, full-width canvas, bottom-up bars, fast decay
                        var series by remember { mutableStateOf(List(60) { 0f }) }
                        LaunchedEffect(isPresented) {
                            while (isPresented) {
                                val s = relayStats.lastSecondRelays.toFloat()
                                val last = series.lastOrNull() ?: 0f
                                // Faster decay and smoothing
                                val v = last * 0.5f + s * 0.5f
                                series = (series + v).takeLast(60)
                                kotlinx.coroutines.delay(400)
                            }
                        }
                        val maxValRaw = series.maxOrNull() ?: 0f
                        val maxVal = if (maxValRaw > 0f) maxValRaw else 0f
                        Box(Modifier.fillMaxWidth().height(48.dp)) {
                            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                                val axisPx = 28.dp.toPx() // leave room on left for ticks
                                val barCount = series.size
                                val availW = (size.width - axisPx).coerceAtLeast(1f)
                                val w = availW / barCount
                                val h = size.height
                                // draw baseline at y=0 (bottom)
                                drawLine(
                                    color = Color(0x33888888),
                                    start = androidx.compose.ui.geometry.Offset(axisPx, h - 1f),
                                    end = androidx.compose.ui.geometry.Offset(size.width, h - 1f),
                                    strokeWidth = 1f
                                )
                                series.forEachIndexed { i, value ->
                                    val ratio = if (maxVal > 0f) (value / maxVal).coerceIn(0f, 1f) else 0f // min always 0
                                    val barHeight = h * ratio
                                    // Draw bars from bottom up, starting after left axis area
                                    drawRect(
                                        color = Color(0xFF00C851),
                                        topLeft = androidx.compose.ui.geometry.Offset(x = axisPx + i * w, y = h - barHeight),
                                        size = androidx.compose.ui.geometry.Size(w, barHeight)
                                    )
                                }
                            }
                            // Y-axis ticks (min/max) in the left margin
                            Text("0", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.align(Alignment.BottomStart).padding(start = 4.dp, bottom = 2.dp))
                            Text("${maxVal.toInt()}", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.align(Alignment.TopStart).padding(start = 4.dp, top = 2.dp))
                            // Y-axis unit label (vertical)
                            Text("p/s", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.align(Alignment.CenterStart).padding(start = 2.dp).rotate(-90f))
                        }
                    }
                }
            }

            // Connected devices
            item {
                Surface(shape = RoundedCornerShape(12.dp), color = colorScheme.surfaceVariant.copy(alpha = 0.2f)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Filled.Devices, contentDescription = null, tint = Color(0xFF4CAF50))
                            Text("connected devices", fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                        val localAddr = remember { meshService.connectionManager.getLocalAdapterAddress() }
                        Text("our device id: ${localAddr ?: "unknown"}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colorScheme.onSurface.copy(alpha = 0.7f))
                        if (connectedDevices.isEmpty()) {
                            Text("none", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colorScheme.onSurface.copy(alpha = 0.6f))
                        } else {
                            connectedDevices.forEach { dev ->
                                Surface(shape = RoundedCornerShape(8.dp), color = colorScheme.surface.copy(alpha = 0.6f)) {
                                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text("${dev.peerID ?: "unknown"} • ${dev.deviceAddress}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                            val roleLabel = if (dev.connectionType == ConnectionType.GATT_SERVER) "as server (we host)" else "as client (we connect)"
                                            Text("${dev.nickname ?: ""} • RSSI: ${dev.rssi ?: "?"} • $roleLabel${if (dev.isDirectConnection) " • direct" else ""}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colorScheme.onSurface.copy(alpha = 0.7f))
                                        }
                                        Text("disconnect", color = Color(0xFFBF1A1A), fontFamily = FontFamily.Monospace, modifier = Modifier.clickable {
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
                            Text("recent scan results", fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                        if (scanResults.isEmpty()) {
                            Text("none", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colorScheme.onSurface.copy(alpha = 0.6f))
                        } else {
                            scanResults.forEach { res ->
                                Surface(shape = RoundedCornerShape(8.dp), color = colorScheme.surface.copy(alpha = 0.6f)) {
                                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text("${res.peerID ?: "unknown"} • ${res.deviceAddress}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                            Text("${res.deviceName ?: ""} • RSSI: ${res.rssi}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colorScheme.onSurface.copy(alpha = 0.7f))
                                        }
                                        Text("connect", color = Color(0xFF00C851), fontFamily = FontFamily.Monospace, modifier = Modifier.clickable {
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
                            Text("debug console", fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.weight(1f))
                            Text("clear", color = Color(0xFFBF1A1A), fontFamily = FontFamily.Monospace, modifier = Modifier.clickable {
                                manager.clearDebugMessages()
                            })
                        }
                        Column(Modifier.heightIn(max = 260.dp).background(colorScheme.surface.copy(alpha = 0.5f)).padding(8.dp)) {
                            debugMessages.takeLast(100).reversed().forEach { msg ->
                                Text("${msg.content}", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}
