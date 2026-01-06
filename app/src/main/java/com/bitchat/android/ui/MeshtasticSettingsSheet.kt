package com.bitchat.android.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.mesh.BluetoothConnectionManager
import com.bitchat.android.service.MeshForegroundService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.bitchat.android.util.AppConstants

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshtasticSettingsSheet(
    isPresented: Boolean,
    onDismiss: () -> Unit
) {
    if (!isPresented) return

    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    
    // Scan state
    var isScanning by remember { mutableStateOf(false) }
    var foundDevices by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    var statusMessage by remember { mutableStateOf("Ready to scan") }
    
    // BLE components
    val bluetoothAdapter = remember { 
        (context.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter 
    }
    
    val scanCallback = remember {
        object : ScanCallback() {
            @SuppressLint("MissingPermission")
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                // Check if device advertises Meshtastic service
                val uuids = result.scanRecord?.serviceUuids
                if (uuids != null && uuids.any { it.uuid == AppConstants.Mesh.Meshtastic.SERVICE_UUID }) {
                    val device = result.device
                    if (!foundDevices.any { it.address == device.address }) {
                        foundDevices = foundDevices + device
                    }
                }
            }
            
            override fun onScanFailed(errorCode: Int) {
                isScanning = false
                statusMessage = "Scan failed: $errorCode"
            }
        }
    }
    
    fun startScan() {
        if (bluetoothAdapter?.isEnabled == true) {
            try {
                isScanning = true
                foundDevices = emptyList()
                statusMessage = "Scanning for Meshtastic devices..."
                bluetoothAdapter.bluetoothLeScanner.startScan(scanCallback)
                
                // Stop after 10s
                scope.launch {
                    delay(10000)
                    if (isScanning) {
                        try {
                            bluetoothAdapter.bluetoothLeScanner.stopScan(scanCallback)
                        } catch (e: Exception) {}
                        isScanning = false
                        statusMessage = if (foundDevices.isEmpty()) "No devices found" else "Scan complete"
                    }
                }
            } catch (e: SecurityException) {
                statusMessage = "Permission error or Bluetooth off"
                isScanning = false
            }
        } else {
            statusMessage = "Bluetooth is disabled"
        }
    }
    
    fun stopScan() {
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {}
        isScanning = false
        statusMessage = "Scan stopped"
    }

    DisposableEffect(Unit) {
        onDispose {
            stopScan()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Meshtastic Setup",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }
            
            Divider()
            
            // Status and Controls
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isScanning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Button(
                    onClick = { if (isScanning) stopScan() else startScan() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        if (isScanning) Icons.Default.Close else Icons.Default.Refresh,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isScanning) "Stop Scan" else "Scan for Devices")
                }
            }
            
            // Device List
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(foundDevices) { device ->
                    MeshtasticDeviceItem(device = device) {
                        stopScan()
                        // Connect logic
                        MeshForegroundService.getInstance()?.let { service ->
                            // Access the hidden BluetoothConnectionManager from the service
                            // This depends on how we can access the manager.
                            // Assuming we might need to expose a method in MeshForegroundService
                            // or access its public properties if available.
                            // For now let's assume we can traverse to it or use a singleton if it existed,
                            // but correct pattern is likely via Service.
                            
                            // Let's reflection or cast if needed, or better, add a method to Service.
                            service.connectToMeshtastic(device)
                            onDismiss()
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun MeshtasticDeviceItem(
    device: BluetoothDevice,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Bluetooth, contentDescription = null)
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = device.name ?: "Unknown Device",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
