package com.bitchat.android.wallet.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bitchat.android.wallet.viewmodel.WalletViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletSettings(
    viewModel: WalletViewModel = viewModel(),
    onBackClick: () -> Unit = {}
) {
    val colorScheme = MaterialTheme.colorScheme
    var showClearDataDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.surface)
    ) {
        // Header
        TopAppBar(
            title = {
                Text(
                    text = "Wallet Settings",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            navigationIcon = {
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = colorScheme.surface,
                titleContentColor = colorScheme.onSurface,
                navigationIconContentColor = colorScheme.onSurface
            )
        )
        
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Wallet Info Section
            item {
                SettingsSection(title = "Wallet Information") {
                    SettingsCard {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            InfoRow("Wallet Version", "0.1.0-beta")
                            Spacer(modifier = Modifier.height(8.dp))
                            InfoRow("Protocol Version", "Cashu v1")
                            Spacer(modifier = Modifier.height(8.dp))
                            InfoRow("Active Mints", "${viewModel.mints.value?.size ?: 0}")
                            Spacer(modifier = Modifier.height(8.dp))
                            InfoRow("Total Transactions", "${viewModel.transactions.value?.size ?: 0}")
                        }
                    }
                }
            }
            
            // Security Section
            item {
                SettingsSection(title = "Security") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsItem(
                            icon = Icons.Filled.FileDownload,
                            title = "Export Wallet Data",
                            description = "Export transaction history and mint info",
                            onClick = { showExportDialog = true }
                        )
                        
                        SettingsItem(
                            icon = Icons.Filled.Security,
                            title = "View Seed Phrase",
                            description = "Display wallet recovery information",
                            onClick = { /* TODO: Implement seed phrase display */ }
                        )
                        
                        SettingsItem(
                            icon = Icons.Filled.Warning,
                            title = "Clear Wallet Data",
                            description = "Remove all wallet data (irreversible)",
                            onClick = { showClearDataDialog = true },
                            textColor = Color(0xFFFF5722)
                        )
                    }
                }
            }
            
            // Network Section
            item {
                SettingsSection(title = "Network") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsItem(
                            icon = Icons.Filled.Public,
                            title = "Network Statistics",
                            description = "View network connectivity information",
                            onClick = { /* TODO: Show network stats */ }
                        )
                        
                        SettingsItem(
                            icon = Icons.Filled.Refresh,
                            title = "Sync All Mints",
                            description = "Refresh mint information and keysets",
                            onClick = { viewModel.syncAllMints() }
                        )
                    }
                }
            }
            
            // Development Section
            item {
                SettingsSection(title = "Development") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsItem(
                            icon = Icons.Filled.BugReport,
                            title = "Debug Mode",
                            description = "Enable detailed logging and debug features",
                            onClick = { /* TODO: Toggle debug mode */ }
                        )
                        
                        SettingsItem(
                            icon = Icons.Filled.Code,
                            title = "Developer Tools",
                            description = "Advanced tools for testing and debugging",
                            onClick = { /* TODO: Open dev tools */ }
                        )
                    }
                }
            }
            
            // About Section
            item {
                SettingsSection(title = "About") {
                    SettingsCard {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Text(
                                text = "bitchat Cashu Wallet",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "A privacy-focused Cashu ecash wallet integrated with bitchat mesh networking.",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Built with the Cashu Development Kit (CDK)",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
        }
    }
    
    // Clear Data Confirmation Dialog
    if (showClearDataDialog) {
        AlertDialog(
            onDismissRequest = { showClearDataDialog = false },
            title = {
                Text(
                    text = "Clear Wallet Data?",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "This will permanently delete all wallet data including:\n\n" +
                           "• Transaction history\n" +
                           "• Saved mints\n" +
                           "• Wallet balance\n" +
                           "• Settings\n\n" +
                           "This action cannot be undone!",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllWalletData()
                        showClearDataDialog = false
                    }
                ) {
                    Text(
                        text = "Clear Data",
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFFFF5722),
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDataDialog = false }) {
                    Text(
                        text = "Cancel",
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        )
    }
    
    // Export Data Dialog
    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = {
                Text(
                    text = "Export Wallet Data",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "This will export your transaction history and mint information to a JSON file. " +
                           "This does NOT include your private keys or wallet secrets.",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.exportWalletData()
                        showExportDialog = false
                    }
                ) {
                    Text(
                        text = "Export",
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF4CAF50),
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text(
                        text = "Cancel",
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column {
        Text(
            text = title,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        content()
    }
}

@Composable
private fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    ) {
        content()
    }
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    textColor: Color = MaterialTheme.colorScheme.onSurface
) {
    SettingsCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = textColor
                )
                Text(
                    text = description,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = textColor.copy(alpha = 0.7f)
                )
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = textColor.copy(alpha = 0.5f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "$label:",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Text(
            text = value,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
