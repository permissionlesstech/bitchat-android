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
    val debugMessages by manager.debugMessages.collectAsState()
    val scanResults by manager.scanResults.collectAsState()
    val connectedDevices by manager.connectedDevices.collectAsState()
    val relayStats by manager.relayStats.collectAsState()

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
                                    if (it) meshService.connectionManager.serverManager.start() else meshService.connectionManager.serverManager.stop()
                                }
                            })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("gatt client", fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                            Switch(checked = gattClientEnabled, onCheckedChange = {
                                manager.setGattClientEnabled(it)
                                scope.launch {
                                    if (it) meshService.connectionManager.clientManager.start() else meshService.connectionManager.clientManager.stop()
                                }
                            })
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
                        if (connectedDevices.isEmpty()) {
                            Text("none", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colorScheme.onSurface.copy(alpha = 0.6f))
                        } else {
                            connectedDevices.forEach { dev ->
                                Surface(shape = RoundedCornerShape(8.dp), color = colorScheme.surface.copy(alpha = 0.6f)) {
                                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text("${dev.peerID ?: "unknown"} • ${dev.deviceAddress}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                            Text("${dev.nickname ?: ""} • RSSI: ${dev.rssi ?: "?"} • ${if (dev.connectionType == ConnectionType.GATT_SERVER) "server" else "client"}${if (dev.isDirectConnection) " • direct" else ""}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colorScheme.onSurface.copy(alpha = 0.7f))
                                        }
                                        Text("disconnect", color = Color(0xFFBF1A1A), fontFamily = FontFamily.Monospace, modifier = Modifier.clickable {
                                            // Disconnect logic: find device and disconnect
                                            // This requires exposing a disconnect method on connection tracker
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
                                            // TODO: Initiate client connection to this device
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
