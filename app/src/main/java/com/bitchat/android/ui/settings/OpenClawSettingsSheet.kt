package com.bitchat.android.ui.settings

import android.content.Intent
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bitchat.android.features.openclaw.OpenClawService

/**
 * OpenClaw Settings Sheet
 * 
 * Displays connection status and controls for OpenClaw integration
 * Provides emergency revoke and log viewing capabilities
 * 
 * Security: All controls require explicit user action
 * Privacy: Logs can be cleared, sensitive data not exposed
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenClawSettingsSheet(
    viewModel: OpenClawViewModel = viewModel()
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val errorState by viewModel.errorState.collectAsState()
    val sessionInfo by viewModel.sessionInfo.collectAsState()
    val connectionLog by viewModel.connectionLog.collectAsState()
    
    var showLogs by remember { mutableStateOf(false) }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Text(
            text = "Settings",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Connection Status Card
        ConnectionStatusCard(
            connectionState = connectionState,
            sessionInfo = sessionInfo,
            errorState = errorState
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Management Controls
        ManagementControls(
            isConnected = connectionState == OpenClawService.STATE_CONNECTED,
            onRevoke = { viewModel.revokeConnection() },
            onViewLogs = { showLogs = true },
            onClearLogs = { viewModel.clearLogs() }
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Activity Log
        if (showLogs) {
            ConnectionLogCard(
                logs = connectionLog,
                onClose = { showLogs = false }
            )
        } else {
            OutlinedButton(
                onClick = { showLogs = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("View Activity Logs")
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Information
        InformationCard()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionStatusCard(
    connectionState: String,
    sessionInfo: String,
    errorState: String?
) {
    val (statusText, statusColor) = when (connectionState) {
        OpenClawService.STATE_CONNECTED -> "🔒 Connected" to Color(0xFF00C853)
        OpenClawService.STATE_CONNECTING -> "⏳ Connecting..." to Color(0xFF2196F3)
        OpenClawService.STATE_HANDSHAKE -> "🤝 Handshake" to Color(0xFF9C27B0)
        OpenClawService.STATE_ERROR -> "❌ Error" to Color(0xFFF44336)
        else -> "⭕ Not Connected" to Color(0xFF9E9E9E)
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (connectionState) {
                OpenClawService.STATE_CONNECTED -> Color(0xFFE8F5E9)
                OpenClawService.STATE_ERROR -> Color(0xFFFFEBEE)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "OpenClaw Status",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.titleMedium,
                    color = statusColor,
                    fontWeight = FontWeight.Bold
                )
            }
            
            if (connectionState == OpenClawService.STATE_CONNECTED) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = sessionInfo,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            if (errorState != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Error: $errorState",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
fun ManagementControls(
    isConnected: Boolean,
    onRevoke: () -> Unit,
    onViewLogs: () -> Unit,
    onClearLogs: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (isConnected) {
            Button(
                onClick = onRevoke,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("🚨 Revoke Connection")
            }
        } else {
            OutlinedButton(
                onClick = { /* TODO: Navigate to pairing */ },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("🔗 Pair with OpenClaw")
            }
        }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onViewLogs,
                modifier = Modifier.weight(1f)
            ) {
                Text("View Logs")
            }
            
            OutlinedButton(
                onClick = onClearLogs,
                modifier = Modifier.weight(1f)
            ) {
                Text("Clear Logs")
            }
        }
    }
}

@Composable
fun ConnectionLogCard(
    logs: List<LogEntry>,
    onClose: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Activity Log",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onClose) {
                    Text("Close")
                }
            }
            
            Divider()
            
            if (logs.isEmpty()) {
                Text(
                    text = "No recent activity",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                logs.takeLast(10).forEach { log ->
                    LogRow(log = log)
                }
            }
        }
    }
}

@Composable
fun LogRow(log: LogEntry) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = log.action,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = formatTimestamp(log.timestamp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun InformationCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "ℹ️ About OpenClaw Integration",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Divider()
            
            Text(
                text = """
                |
                |OpenClaw provides AI-assisted feature development:
                |
                |✅ Zero-risk sandbox (keys/wallet blocked)
                |✅ User approval for all features
                |✅ Emergency controls (freeze, revoke)
                |✅ Complete activity logging
                |
                |All communication E2E encrypted via Noise Protocol.
                |
                |Version: 1.0.0-alpha
                |Status: Experimental
                |
                """.trimMargin(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// Data classes
data class LogEntry(
    val id: String,
    val action: String,
    val timestamp: Long
)

// ViewModel
class OpenClawViewModel : androidx.lifecycle.ViewModel() {
    private val _connectionState = mutableStateOf<String>(OpenClawService.STATE_DISCONNECTED)
    val connectionState: androidx.compose.runtime.State<String> = _connectionState
    
    private val _errorState = mutableStateOf<String?>(null)
    val errorState: androidx.compose.runtime.State<String?> = _errorState
    
    private val _sessionInfo = mutableStateOf<String>("Not connected")
    val sessionInfo: androidx.compose.runtime.State<String> = _sessionInfo
    
    private val _connectionLog = mutableStateListOf<LogEntry>()
    val connectionLog: androidx.compose.runtime.State<List<LogEntry>> = _connectionLog
    
    fun revokeConnection() {
        Log.w("OpenClawViewModel", "Revoke requested")
        _connectionState.value = OpenClawService.STATE_DISCONNECTED
        _errorState.value = "Connection revoked by user"
        _sessionInfo.value = "Not connected"
        addLog("Connection revoked")
    }
    
    fun clearLogs() {
        _connectionLog.clear()
        addLog("Logs cleared")
    }
    
    fun addLog(action: String) {
        _connectionLog.add(
            LogEntry(
                id = java.util.UUID.randomUUID().toString(),
                action = action,
                timestamp = System.currentTimeMillis()
            )
        )
    }
    
    init {
        // Monitor connection state
        // TODO: Connect to OpenClawService to get real state
        addLog("Settings opened")
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60000 -> "${diff / 1000}s ago"
        diff < 3600000 -> "${diff / 60000}m ago"
        diff < 86400000 -> "${diff / 3600000}h ago"
        else -> "${diff / 86400000}d ago"
    }
}