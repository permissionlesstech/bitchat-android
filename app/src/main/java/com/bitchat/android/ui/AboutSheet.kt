package com.bitchat.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.nostr.NostrProofOfWork
import com.bitchat.android.nostr.PoWPreferenceManager

/**
 * About Sheet for bitchat app information
 * Matches the design language of LocationChannelsSheet
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSheet(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    // Get version name from package info
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            "1.0.0" // fallback version
        }
    }
    
    // Bottom sheet state
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = false
    )
    
    // Color scheme matching LocationChannelsSheet
    val colorScheme = MaterialTheme.colorScheme
    val isDark = colorScheme.background.red + colorScheme.background.green + colorScheme.background.blue < 1.5f
    val standardBlue = Color(0xFF007AFF) // iOS blue
    val standardGreen = if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D) // iOS green
    
    if (isPresented) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            modifier = modifier
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Text(
                                text = "bitchat",
                                fontSize = 18.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium,
                                color = colorScheme.onSurface
                            )
                            
                            Text(
                                text = "v$versionName",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colorScheme.onSurface.copy(alpha = 0.5f),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    baselineShift = BaselineShift(0.1f)
                                )
                            )
                        }
                        
                        Text(
                            text = "decentralized mesh messaging with end-to-end encryption",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
                
                // Features section
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        FeatureCard(
                            icon = Icons.Filled.Bluetooth,
                            iconColor = standardBlue,
                            title = "offline mesh chat",
                            description = "communicate directly via bluetooth le without internet or servers. messages relay through nearby devices to extend range.",
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        FeatureCard(
                            icon = Icons.Filled.Public,
                            iconColor = standardGreen,
                            title = "online geohash channels",
                            description = "connect with people in your area using geohash-based channels. extend the mesh using public internet relays.",
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        FeatureCard(
                            icon = Icons.Filled.Lock,
                            iconColor = if (isDark) Color(0xFFFFD60A) else Color(0xFFF5A623),
                            title = "end-to-end encryption",
                            description = "private messages are encrypted. channel messages are public.",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // Appearance section (theme toggle)
                item {
                    val themePref by com.bitchat.android.ui.theme.ThemePreferenceManager.themeFlow.collectAsState()
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "appearance",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            color = colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = themePref.isSystem,
                                onClick = { com.bitchat.android.ui.theme.ThemePreferenceManager.set(context, com.bitchat.android.ui.theme.ThemePreference.System) },
                                label = { Text("system", fontFamily = FontFamily.Monospace) }
                            )
                            FilterChip(
                                selected = themePref.isLight,
                                onClick = { com.bitchat.android.ui.theme.ThemePreferenceManager.set(context, com.bitchat.android.ui.theme.ThemePreference.Light) },
                                label = { Text("light", fontFamily = FontFamily.Monospace) }
                            )
                            FilterChip(
                                selected = themePref.isDark,
                                onClick = { com.bitchat.android.ui.theme.ThemePreferenceManager.set(context, com.bitchat.android.ui.theme.ThemePreference.Dark) },
                                label = { Text("dark", fontFamily = FontFamily.Monospace) }
                            )
                        }
                    }
                }

                // Proof of Work section
                item {
                    val context = LocalContext.current
                    
                    // Initialize PoW preferences if not already done
                    LaunchedEffect(Unit) {
                        PoWPreferenceManager.init(context)
                    }
                    
                    val powEnabled by PoWPreferenceManager.powEnabled.collectAsState()
                    val powDifficulty by PoWPreferenceManager.powDifficulty.collectAsState()
                    
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "proof of work",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            color = colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                        
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilterChip(
                                selected = !powEnabled,
                                onClick = { PoWPreferenceManager.setPowEnabled(false) },
                                label = { Text("pow off", fontFamily = FontFamily.Monospace) }
                            )
                            FilterChip(
                                selected = powEnabled,
                                onClick = { PoWPreferenceManager.setPowEnabled(true) },
                                label = { 
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("pow on", fontFamily = FontFamily.Monospace)
                                        // Show current difficulty
                                        if (powEnabled) {
                                            Surface(
                                                color = if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D),
                                                shape = RoundedCornerShape(50)
                                            ) { Box(Modifier.size(8.dp)) }
                                        }
                                    }
                                }
                            )
                        }
                        
                        Text(
                            text = "add proof of work to geohash messages for spam deterrence.",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        
                        // Show difficulty slider when enabled
                        if (powEnabled) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "difficulty: $powDifficulty bits (~${NostrProofOfWork.estimateMiningTime(powDifficulty)})",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                                
                                Slider(
                                    value = powDifficulty.toFloat(),
                                    onValueChange = { PoWPreferenceManager.setPowDifficulty(it.toInt()) },
                                    valueRange = 0f..20f,
                                    steps = 21, // 20 discrete values (0-20)
                                    colors = SliderDefaults.colors(
                                        thumbColor = if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D),
                                        activeTrackColor = if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D)
                                    )
                                )
                                
                                // Show difficulty description
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = colorScheme.surfaceVariant.copy(alpha = 0.25f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = "difficulty $powDifficulty requires ~${NostrProofOfWork.estimateWork(powDifficulty)} hash attempts",
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = colorScheme.onSurface.copy(alpha = 0.7f)
                                        )
                                        Text(
                                            text = when {
                                                powDifficulty == 0 -> "no proof of work required"
                                                powDifficulty <= 8 -> "very low - minimal spam protection"
                                                powDifficulty <= 12 -> "low - basic spam protection"
                                                powDifficulty <= 16 -> "medium - good spam protection"
                                                powDifficulty <= 20 -> "high - strong spam protection"
                                                powDifficulty <= 24 -> "very high - may cause delays"
                                                else -> "extreme - significant computation required"
                                            },
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = colorScheme.onSurface.copy(alpha = 0.6f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Network (Tor) section
                item {
                    val ctx = LocalContext.current
                    val torMode = remember { mutableStateOf(com.bitchat.android.net.TorPreferenceManager.get(ctx)) }
                    val torStatus by com.bitchat.android.net.TorManager.statusFlow.collectAsState()
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "network",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            color = colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(
                                selected = torMode.value == com.bitchat.android.net.TorMode.OFF,
                                onClick = {
                                    torMode.value = com.bitchat.android.net.TorMode.OFF
                                    com.bitchat.android.net.TorPreferenceManager.set(ctx, torMode.value)
                                },
                                label = { Text("tor off", fontFamily = FontFamily.Monospace) }
                            )
                            FilterChip(
                                selected = torMode.value == com.bitchat.android.net.TorMode.ON,
                                onClick = {
                                    torMode.value = com.bitchat.android.net.TorMode.ON
                                    com.bitchat.android.net.TorPreferenceManager.set(ctx, torMode.value)
                                },
                                label = { 
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("tor on", fontFamily = FontFamily.Monospace)
                                        // Status indicator (red/orange/green) moved inside the "tor on" button
                                        val statusColor = when {
                                            torStatus.running && torStatus.bootstrapPercent < 100 -> Color(0xFFFF9500)
                                            torStatus.running && torStatus.bootstrapPercent >= 100 -> if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D)
                                            else -> Color.Red
                                        }
                                        Surface(
                                            color = statusColor,
                                            shape = RoundedCornerShape(50)
                                        ) { Box(Modifier.size(8.dp)) }
                                    }
                                }
                            )
                        }
                        Text(
                            text = "route internet over tor for enhanced privacy.",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colorScheme.onSurface.copy(alpha = 0.6f)
                        )

                        // Debug status (temporary)
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = colorScheme.surfaceVariant.copy(alpha = 0.25f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "tor status: " +
                                            (if (torStatus.running) "running" else "stopped") +
                                            ", bootstrap=" + torStatus.bootstrapPercent + "%",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = colorScheme.onSurface.copy(alpha = 0.75f)
                                )
                                val last = torStatus.lastLogLine
                                if (last.isNotEmpty()) {
                                    Text(
                                        text = "last: " + last.take(160),
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                    }
                }
                
                // Emergency warning
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color.Red.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = "Warning",
                                tint = Color(0xFFBF1A1A),
                                modifier = Modifier.size(16.dp)
                            )
                            
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "emergency data deletion",
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFFBF1A1A)
                                )
                                
                                Text(
                                    text = "tip: triple-click the app title to emergency delete all stored data including messages, keys, and settings.",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
                
                // Version and footer space
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "open source • privacy first • decentralized",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                        
                        // Add extra space at bottom for gesture area
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun FeatureCard(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    description: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = iconColor,
                modifier = Modifier.size(20.dp)
            )
            
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Text(
                    text = description,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    lineHeight = 15.sp
                )
            }
        }
    }
}

@Composable
private fun FeatureItem(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "•",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        
        Text(
            text = text,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Password prompt dialog for password-protected channels
 * Kept as dialog since it requires user input
 */
@Composable
fun PasswordPromptDialog(
    show: Boolean,
    channelName: String?,
    passwordInput: String,
    onPasswordChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (show && channelName != null) {
        val colorScheme = MaterialTheme.colorScheme
        
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    text = "Enter Channel Password",
                    style = MaterialTheme.typography.titleMedium,
                    color = colorScheme.onSurface
                )
            },
            text = {
                Column {
                    Text(
                        text = "Channel $channelName is password protected. Enter the password to join.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = onPasswordChange,
                        label = { Text("Password", style = MaterialTheme.typography.bodyMedium) },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colorScheme.primary,
                            unfocusedBorderColor = colorScheme.outline
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirm) {
                    Text(
                        text = "Join",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.primary
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(
                        text = "Cancel",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurface
                    )
                }
            },
            containerColor = colorScheme.surface,
            tonalElevation = 8.dp
        )
    }
}
