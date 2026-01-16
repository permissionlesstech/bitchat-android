package com.bitchat.android.ui

import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.bitchat.android.R
import com.bitchat.android.core.ui.component.button.CloseButton
import com.bitchat.android.core.ui.component.sheet.BitchatBottomSheet
import com.bitchat.android.hotspot.HotspotActivity
import com.bitchat.android.net.ArtiTorManager
import com.bitchat.android.net.TorMode
import com.bitchat.android.net.TorPreferenceManager
import com.bitchat.android.nostr.NostrProofOfWork
import com.bitchat.android.nostr.PoWPreferenceManager
import com.bitchat.android.util.UniversalApkManager
import com.bitchat.android.util.ApkSharingUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Feature row for displaying app capabilities
 */
@Composable
private fun FeatureRow(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    val colorScheme = MaterialTheme.colorScheme
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colorScheme.primary,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(22.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurface.copy(alpha = 0.6f),
                lineHeight = 18.sp
            )
        }
    }
}

/**
 * Theme selection chip with Apple-like styling
 */
@Composable
private fun ThemeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val isDark = colorScheme.background.red + colorScheme.background.green + colorScheme.background.blue < 1.5f
    
    Surface(
        modifier = modifier,
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) {
            if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D)
        } else {
            colorScheme.surfaceVariant.copy(alpha = 0.5f)
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) Color.White else colorScheme.onSurface.copy(alpha = 0.8f)
            )
        }
    }
}

/**
 * Unified settings toggle row with icon, title, subtitle, and switch
 * Apple-like design with proper spacing
 */
@Composable
private fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    statusIndicator: (@Composable () -> Unit)? = null
) {
    val colorScheme = MaterialTheme.colorScheme
    val isDark = colorScheme.background.red + colorScheme.background.green + colorScheme.background.blue < 1.5f
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) colorScheme.primary else colorScheme.onSurface.copy(alpha = 0.3f),
            modifier = Modifier.size(22.dp)
        )
        
        Spacer(modifier = Modifier.width(14.dp))
        
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) colorScheme.onSurface else colorScheme.onSurface.copy(alpha = 0.4f)
                )
                statusIndicator?.invoke()
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurface.copy(alpha = if (enabled) 0.6f else 0.3f),
                lineHeight = 16.sp
            )
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Switch(
            checked = checked,
            onCheckedChange = { if (enabled) onCheckedChange(it) },
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D),
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = colorScheme.surfaceVariant
            )
        )
    }
}

/**
 * Apple-like About/Settings Sheet with high-quality design
 * Professional UX optimized for checkout scenarios
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSheet(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    onShowDebug: (() -> Unit)? = null,
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

    val lazyListState = rememberLazyListState()
    val isScrolled by remember {
        derivedStateOf {
            lazyListState.firstVisibleItemIndex > 0 || lazyListState.firstVisibleItemScrollOffset > 0
        }
    }
    val topBarAlpha by animateFloatAsState(
        targetValue = if (isScrolled) 0.98f else 0f,
        label = "topBarAlpha"
    )

    val colorScheme = MaterialTheme.colorScheme
    val isDark = colorScheme.background.red + colorScheme.background.green + colorScheme.background.blue < 1.5f
    
    if (isPresented) {
        BitchatBottomSheet(
            modifier = modifier,
            onDismissRequest = onDismiss,
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 80.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // Header Section - App Identity
                    item(key = "header") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 28.sp,
                                    letterSpacing = 1.sp
                                ),
                                color = colorScheme.onBackground
                            )
                            Text(
                                text = stringResource(R.string.version_prefix, versionName ?: ""),
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colorScheme.onBackground.copy(alpha = 0.5f)
                            )
                            Text(
                                text = stringResource(R.string.about_tagline),
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colorScheme.onBackground.copy(alpha = 0.6f),
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }

                    // Features Section - Grouped Card
                    item(key = "features") {
                        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                            Text(
                                text = stringResource(R.string.about_appearance).uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = colorScheme.onBackground.copy(alpha = 0.5f),
                                letterSpacing = 0.5.sp,
                                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                            )
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = colorScheme.surface,
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column {
                                    FeatureRow(
                                        icon = Icons.Filled.Bluetooth,
                                        title = stringResource(R.string.about_offline_mesh_title),
                                        subtitle = stringResource(R.string.about_offline_mesh_desc)
                                    )
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 56.dp),
                                        color = colorScheme.outline.copy(alpha = 0.12f)
                                    )
                                    FeatureRow(
                                        icon = Icons.Default.Public,
                                        title = stringResource(R.string.about_online_geohash_title),
                                        subtitle = stringResource(R.string.about_online_geohash_desc)
                                    )
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 56.dp),
                                        color = colorScheme.outline.copy(alpha = 0.12f)
                                    )
                                    FeatureRow(
                                        icon = Icons.Default.Lock,
                                        title = stringResource(R.string.about_e2e_title),
                                        subtitle = stringResource(R.string.about_e2e_desc)
                                    )
                                }
                            }
                        }
                    }

                    // Appearance Section
                    item(key = "appearance") {
                        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                            Text(
                                text = "THEME",
                                style = MaterialTheme.typography.labelSmall,
                                color = colorScheme.onBackground.copy(alpha = 0.5f),
                                letterSpacing = 0.5.sp,
                                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                            )
                            val themePref by com.bitchat.android.ui.theme.ThemePreferenceManager.themeFlow.collectAsState()
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = colorScheme.surface,
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    ThemeChip(
                                        label = stringResource(R.string.about_system),
                                        selected = themePref.isSystem,
                                        onClick = { com.bitchat.android.ui.theme.ThemePreferenceManager.set(context, com.bitchat.android.ui.theme.ThemePreference.System) },
                                        modifier = Modifier.weight(1f)
                                    )
                                    ThemeChip(
                                        label = stringResource(R.string.about_light),
                                        selected = themePref.isLight,
                                        onClick = { com.bitchat.android.ui.theme.ThemePreferenceManager.set(context, com.bitchat.android.ui.theme.ThemePreference.Light) },
                                        modifier = Modifier.weight(1f)
                                    )
                                    ThemeChip(
                                        label = stringResource(R.string.about_dark),
                                        selected = themePref.isDark,
                                        onClick = { com.bitchat.android.ui.theme.ThemePreferenceManager.set(context, com.bitchat.android.ui.theme.ThemePreference.Dark) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }

                    // Settings Section - Unified Card with Toggles
                    item(key = "settings") {
                        LaunchedEffect(Unit) { PoWPreferenceManager.init(context) }
                        val powEnabled by PoWPreferenceManager.powEnabled.collectAsState()
                        val powDifficulty by PoWPreferenceManager.powDifficulty.collectAsState()
                        var backgroundEnabled by remember { mutableStateOf(com.bitchat.android.service.MeshServicePreferences.isBackgroundEnabled(true)) }
                        val torMode = remember { mutableStateOf(TorPreferenceManager.get(context)) }
                        val torProvider = remember { ArtiTorManager.getInstance() }
                        val torStatus by torProvider.statusFlow.collectAsState()
                        val torAvailable = remember { torProvider.isTorAvailable() }

                        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                            Text(
                                text = "SETTINGS",
                                style = MaterialTheme.typography.labelSmall,
                                color = colorScheme.onBackground.copy(alpha = 0.5f),
                                letterSpacing = 0.5.sp,
                                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                            )
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = colorScheme.surface,
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column {
                                    // Background Mode Toggle
                                    SettingsToggleRow(
                                        icon = Icons.Filled.Bluetooth,
                                        title = stringResource(R.string.about_background_title),
                                        subtitle = stringResource(R.string.about_background_desc),
                                        checked = backgroundEnabled,
                                        onCheckedChange = { enabled ->
                                            backgroundEnabled = enabled
                                            com.bitchat.android.service.MeshServicePreferences.setBackgroundEnabled(enabled)
                                            if (!enabled) {
                                                com.bitchat.android.service.MeshForegroundService.stop(context)
                                            } else {
                                                com.bitchat.android.service.MeshForegroundService.start(context)
                                            }
                                        }
                                    )
                                    
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 56.dp),
                                        color = colorScheme.outline.copy(alpha = 0.12f)
                                    )
                                    
                                    // Proof of Work Toggle
                                    SettingsToggleRow(
                                        icon = Icons.Filled.Speed,
                                        title = stringResource(R.string.about_pow),
                                        subtitle = stringResource(R.string.about_pow_tip),
                                        checked = powEnabled,
                                        onCheckedChange = { PoWPreferenceManager.setPowEnabled(it) }
                                    )
                                    
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 56.dp),
                                        color = colorScheme.outline.copy(alpha = 0.12f)
                                    )
                                    
                                    // Tor Toggle
                                    SettingsToggleRow(
                                        icon = Icons.Filled.Security,
                                        title = "Tor Network",
                                        subtitle = stringResource(R.string.about_tor_route),
                                        checked = torMode.value == TorMode.ON,
                                        onCheckedChange = { enabled ->
                                            if (torAvailable) {
                                                torMode.value = if (enabled) TorMode.ON else TorMode.OFF
                                                TorPreferenceManager.set(context, torMode.value)
                                            }
                                        },
                                        enabled = torAvailable,
                                        statusIndicator = if (torMode.value == TorMode.ON) {
                                            {
                                                val statusColor = when {
                                                    torStatus.running && torStatus.bootstrapPercent >= 100 -> if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D)
                                                    torStatus.running -> Color(0xFFFF9500)
                                                    else -> Color(0xFFFF3B30)
                                                }
                                                Surface(
                                                    color = statusColor,
                                                    shape = CircleShape,
                                                    modifier = Modifier.size(8.dp)
                                                ) {}
                                            }
                                        } else null
                                    )

                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 56.dp),
                                        color = colorScheme.outline.copy(alpha = 0.12f)
                                    )

                                    // === Prepare App for Sharing Section ===
                                    val scope = rememberCoroutineScope()
                                    val apkManager = remember { UniversalApkManager(context) }
                                    var apkStatus by remember { mutableStateOf<ApkPreparationStatus>(ApkPreparationStatus.Loading) }
                                    var downloadProgress by remember { mutableStateOf(0) }
                                    var showPrepareDialog by remember { mutableStateOf(false) }
                                    var showDeleteDialog by remember { mutableStateOf(false) }

                                    // Check APK status on launch
                                    LaunchedEffect(Unit) {
                                        apkStatus = checkApkStatus(apkManager)
                                    }

                                    // Prepare App for Sharing Row
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = apkStatus !is ApkPreparationStatus.Downloading) {
                                                when (val status = apkStatus) {
                                                    is ApkPreparationStatus.NotDownloaded -> showPrepareDialog = true
                                                    is ApkPreparationStatus.UpdateAvailable -> showPrepareDialog = true
                                                    else -> {}
                                                }
                                            }
                                            .padding(horizontal = 16.dp, vertical = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CloudDownload,
                                            contentDescription = null,
                                            tint = colorScheme.primary,
                                            modifier = Modifier.size(22.dp)
                                        )

                                        Spacer(modifier = Modifier.width(14.dp))

                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.prepare_apk_title),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                color = colorScheme.onSurface
                                            )
                                            Text(
                                                text = when (val status = apkStatus) {
                                                    is ApkPreparationStatus.Loading -> "Checking..."
                                                    is ApkPreparationStatus.NotDownloaded -> stringResource(R.string.prepare_apk_status_not_downloaded)
                                                    is ApkPreparationStatus.Ready -> stringResource(R.string.prepare_apk_status_ready) + " • ${status.version} • ${status.sizeMB} MB"
                                                    is ApkPreparationStatus.UpdateAvailable -> stringResource(R.string.prepare_apk_status_update_available) + " (${status.newVersion})"
                                                    is ApkPreparationStatus.Downloading -> stringResource(R.string.prepare_apk_status_downloading, downloadProgress)
                                                    is ApkPreparationStatus.Error -> status.message
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = when (apkStatus) {
                                                    is ApkPreparationStatus.Error -> colorScheme.error
                                                    is ApkPreparationStatus.UpdateAvailable -> colorScheme.primary
                                                    else -> colorScheme.onSurface.copy(alpha = 0.6f)
                                                },
                                                lineHeight = 16.sp
                                            )
                                        }

                                        // Action buttons
                                        when (val status = apkStatus) {
                                            is ApkPreparationStatus.Downloading -> {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(20.dp),
                                                    strokeWidth = 2.dp
                                                )
                                            }
                                            is ApkPreparationStatus.Ready, is ApkPreparationStatus.UpdateAvailable -> {
                                                androidx.compose.material3.IconButton(
                                                    onClick = { showDeleteDialog = true },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Delete,
                                                        contentDescription = "Delete",
                                                        tint = colorScheme.error,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                            }
                                            else -> {}
                                        }
                                    }

                                    // Prepare Dialog
                                    if (showPrepareDialog) {
                                        val status = apkStatus
                                        val sizeMB = when (status) {
                                            is ApkPreparationStatus.NotDownloaded -> status.sizeMB
                                            is ApkPreparationStatus.UpdateAvailable -> status.newSizeMB
                                            else -> 47
                                        }
                                        AlertDialog(
                                            onDismissRequest = { showPrepareDialog = false },
                                            title = {
                                                Text(
                                                    text = if (status is ApkPreparationStatus.UpdateAvailable) {
                                                        stringResource(R.string.prepare_apk_update_dialog_title)
                                                    } else {
                                                        stringResource(R.string.prepare_apk_dialog_title)
                                                    },
                                                    style = MaterialTheme.typography.titleLarge
                                                )
                                            },
                                            text = {
                                                Text(
                                                    text = if (status is ApkPreparationStatus.UpdateAvailable) {
                                                        stringResource(R.string.prepare_apk_update_dialog_message, status.newVersion, status.currentVersion)
                                                    } else {
                                                        stringResource(R.string.prepare_apk_dialog_message, sizeMB)
                                                    },
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                            },
                                            confirmButton = {
                                                Button(onClick = {
                                                    showPrepareDialog = false
                                                    apkStatus = ApkPreparationStatus.Downloading
                                                    scope.launch {
                                                        downloadUniversalApk(apkManager, { progress ->
                                                            downloadProgress = progress
                                                        }) { result ->
                                                            apkStatus = result
                                                        }
                                                    }
                                                }) {
                                                    Text(stringResource(R.string.prepare_apk_dialog_confirm))
                                                }
                                            },
                                            dismissButton = {
                                                TextButton(onClick = { showPrepareDialog = false }) {
                                                    Text(stringResource(R.string.cancel))
                                                }
                                            },
                                            containerColor = colorScheme.surface
                                        )
                                    }

                                    // Delete Dialog
                                    if (showDeleteDialog) {
                                        val sizeMB = (apkStatus as? ApkPreparationStatus.Ready)?.sizeMB ?: 0
                                        AlertDialog(
                                            onDismissRequest = { showDeleteDialog = false },
                                            title = {
                                                Text(
                                                    text = stringResource(R.string.prepare_apk_delete_confirm),
                                                    style = MaterialTheme.typography.titleLarge
                                                )
                                            },
                                            text = {
                                                Text(
                                                    text = stringResource(R.string.prepare_apk_delete_message, sizeMB),
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                            },
                                            confirmButton = {
                                                Button(
                                                    onClick = {
                                                        showDeleteDialog = false
                                                        apkManager.deleteCachedApk()
                                                        scope.launch {
                                                            apkStatus = checkApkStatus(apkManager)
                                                        }
                                                    },
                                                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                                        containerColor = colorScheme.error
                                                    )
                                                ) {
                                                    Text("Delete")
                                                }
                                            },
                                            dismissButton = {
                                                TextButton(onClick = { showDeleteDialog = false }) {
                                                    Text(stringResource(R.string.cancel))
                                                }
                                            },
                                            containerColor = colorScheme.surface
                                        )
                                    }

                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 56.dp),
                                        color = colorScheme.outline.copy(alpha = 0.12f)
                                    )

                                    // === Share via Hotspot Row ===
                                    var showHotspotRequiredDialog by remember { mutableStateOf(false) }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                val cachedApk = apkManager.getCachedApk()
                                                if (cachedApk != null) {
                                                    val intent = Intent(context, HotspotActivity::class.java)
                                                    intent.putExtra(HotspotActivity.EXTRA_APK_PATH, cachedApk.absolutePath)
                                                    context.startActivity(intent)
                                                } else {
                                                    showHotspotRequiredDialog = true
                                                }
                                            }
                                            .padding(horizontal = 16.dp, vertical = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Wifi,
                                            contentDescription = null,
                                            tint = colorScheme.primary,
                                            modifier = Modifier.size(22.dp)
                                        )

                                        Spacer(modifier = Modifier.width(14.dp))

                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.hotspot_share_via),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                color = colorScheme.onSurface
                                            )
                                            Text(
                                                text = "Create Wi-Fi hotspot to share offline",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = colorScheme.onSurface.copy(alpha = 0.6f),
                                                lineHeight = 16.sp
                                            )
                                        }

                                        Icon(
                                            imageVector = Icons.Default.ChevronRight,
                                            contentDescription = null,
                                            tint = colorScheme.onSurface.copy(alpha = 0.4f),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    // Required Dialog
                                    if (showHotspotRequiredDialog) {
                                        AlertDialog(
                                            onDismissRequest = { showHotspotRequiredDialog = false },
                                            title = { Text("APK Not Ready") },
                                            text = { Text(stringResource(R.string.prepare_apk_required)) },
                                            confirmButton = {
                                                Button(onClick = { showHotspotRequiredDialog = false }) {
                                                    Text("OK")
                                                }
                                            },
                                            containerColor = colorScheme.surface
                                        )
                                    }

                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 56.dp),
                                        color = colorScheme.outline.copy(alpha = 0.12f)
                                    )

                                    // === Share via Bluetooth/Email Row (Fallback) ===
                                    var showShareApkDialog by remember { mutableStateOf(false) }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { showShareApkDialog = true }
                                            .padding(horizontal = 16.dp, vertical = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Bluetooth,
                                            contentDescription = null,
                                            tint = colorScheme.primary,
                                            modifier = Modifier.size(22.dp)
                                        )

                                        Spacer(modifier = Modifier.width(14.dp))

                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.hotspot_share_other),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                color = colorScheme.onSurface
                                            )
                                            Text(
                                                text = "Use standard Android sharing",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = colorScheme.onSurface.copy(alpha = 0.6f),
                                                lineHeight = 16.sp
                                            )
                                        }

                                        Icon(
                                            imageVector = Icons.Default.ChevronRight,
                                            contentDescription = null,
                                            tint = colorScheme.onSurface.copy(alpha = 0.4f),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    // APK Share Dialog
                                    ApkShareExplanationDialog(
                                        show = showShareApkDialog,
                                        onConfirm = {
                                            showShareApkDialog = false
                                            scope.launch {
                                                shareApk(context)
                                            }
                                        },
                                        onDismiss = { showShareApkDialog = false }
                                    )

                                }
                            }
                            
                            // Tor unavailable hint
                            if (!torAvailable) {
                                Text(
                                    text = stringResource(R.string.tor_not_available_in_this_build),
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = colorScheme.onBackground.copy(alpha = 0.5f),
                                    modifier = Modifier.padding(start = 16.dp, top = 8.dp)
                                )
                            }
                        }
                    }

                    // PoW Difficulty Slider (when enabled)
                    item(key = "pow_slider") {
                        val powEnabled by PoWPreferenceManager.powEnabled.collectAsState()
                        val powDifficulty by PoWPreferenceManager.powDifficulty.collectAsState()
                        
                        if (powEnabled) {
                            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = colorScheme.surface,
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Difficulty",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                color = colorScheme.onSurface
                                            )
                                            Text(
                                                text = "$powDifficulty bits • ${NostrProofOfWork.estimateMiningTime(powDifficulty)}",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = FontFamily.Monospace,
                                                color = colorScheme.onSurface.copy(alpha = 0.6f)
                                            )
                                        }
                                        
                                        Slider(
                                            value = powDifficulty.toFloat(),
                                            onValueChange = { PoWPreferenceManager.setPowDifficulty(it.toInt()) },
                                            valueRange = 0f..32f,
                                            steps = 31,
                                            colors = SliderDefaults.colors(
                                                thumbColor = if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D),
                                                activeTrackColor = if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D)
                                            )
                                        )
                                        
                                        Text(
                                            text = when {
                                                powDifficulty == 0 -> stringResource(R.string.about_pow_desc_none)
                                                powDifficulty <= 8 -> stringResource(R.string.about_pow_desc_very_low)
                                                powDifficulty <= 12 -> stringResource(R.string.about_pow_desc_low)
                                                powDifficulty <= 16 -> stringResource(R.string.about_pow_desc_medium)
                                                powDifficulty <= 20 -> stringResource(R.string.about_pow_desc_high)
                                                powDifficulty <= 24 -> stringResource(R.string.about_pow_desc_very_high)
                                                else -> stringResource(R.string.about_pow_desc_extreme)
                                            },
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = colorScheme.onSurface.copy(alpha = 0.5f)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Tor Status (when enabled)
                    item(key = "tor_status") {
                        val torMode = remember { mutableStateOf(TorPreferenceManager.get(context)) }
                        val torProvider = remember { ArtiTorManager.getInstance() }
                        val torStatus by torProvider.statusFlow.collectAsState()
                        
                        if (torMode.value == TorMode.ON) {
                            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = colorScheme.surface,
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            val statusColor = when {
                                                torStatus.running && torStatus.bootstrapPercent >= 100 -> if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D)
                                                torStatus.running -> Color(0xFFFF9500)
                                                else -> Color(0xFFFF3B30)
                                            }
                                            Surface(color = statusColor, shape = CircleShape, modifier = Modifier.size(10.dp)) {}
                                            Text(
                                                text = if (torStatus.running) "Connected (${torStatus.bootstrapPercent}%)" else "Disconnected",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                color = colorScheme.onSurface
                                            )
                                        }
                                        if (torStatus.lastLogLine.isNotEmpty()) {
                                            Text(
                                                text = torStatus.lastLogLine.take(120),
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = colorScheme.onSurface.copy(alpha = 0.5f),
                                                maxLines = 2
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Emergency Warning
                    item(key = "warning") {
                        Surface(
                            modifier = Modifier
                                .padding(horizontal = 20.dp)
                                .fillMaxWidth(),
                            color = colorScheme.error.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Warning,
                                    contentDescription = null,
                                    tint = colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = stringResource(R.string.about_emergency_title),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colorScheme.error
                                    )
                                    Text(
                                        text = stringResource(R.string.about_emergency_tip),
                                        fontSize = 13.sp,
                                        color = colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }

                    // Footer
                    item(key = "footer") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (onShowDebug != null) {
                                TextButton(onClick = onShowDebug) {
                                    Text(
                                        text = stringResource(R.string.about_debug_settings),
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = colorScheme.primary
                                    )
                                }
                            }
                            Text(
                                text = stringResource(R.string.about_footer),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                        }
                    }
                }

                // TopBar
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(64.dp)
                        .background(MaterialTheme.colorScheme.background.copy(alpha = topBarAlpha))
                ) {
                    CloseButton(
                        onClick = onDismiss,
                        modifier = modifier
                            .align(Alignment.CenterEnd)
                            .padding(horizontal = 16.dp),
                    )
                }
            }
        }
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
                    text = stringResource(R.string.pwd_prompt_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = colorScheme.onSurface
                )
            },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.pwd_prompt_message, channelName ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = onPasswordChange,
                        label = { Text(stringResource(R.string.pwd_label), style = MaterialTheme.typography.bodyMedium) },
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
                        text = stringResource(R.string.join),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.primary
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(
                        text = stringResource(R.string.cancel),
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

/**
 * Status of universal APK preparation.
 */
private sealed class ApkPreparationStatus {
    object Loading : ApkPreparationStatus()
    data class NotDownloaded(val sizeMB: Int) : ApkPreparationStatus()
    data class Ready(val version: String, val sizeMB: Int) : ApkPreparationStatus()
    data class UpdateAvailable(
        val currentVersion: String,
        val newVersion: String,
        val newSizeMB: Int
    ) : ApkPreparationStatus()
    object Downloading : ApkPreparationStatus()
    data class Error(val message: String) : ApkPreparationStatus()
}

/**
 * Check the status of the universal APK.
 */
private suspend fun checkApkStatus(apkManager: UniversalApkManager): ApkPreparationStatus {
    return withContext(Dispatchers.IO) {
        try {
            val updateStatus = apkManager.checkForUpdate()
            when (updateStatus) {
                is UniversalApkManager.UpdateStatus.NotDownloaded -> {
                    ApkPreparationStatus.NotDownloaded(
                        sizeMB = (updateStatus.latestRelease.universalApkSize / 1024 / 1024).toInt()
                    )
                }
                is UniversalApkManager.UpdateStatus.UpToDate -> {
                    val info = apkManager.getCachedApkInfo()
                    if (info != null) {
                        ApkPreparationStatus.Ready(
                            version = info.version,
                            sizeMB = (info.size / 1024 / 1024).toInt()
                        )
                    } else {
                        ApkPreparationStatus.Error("Cached APK info not found")
                    }
                }
                is UniversalApkManager.UpdateStatus.UpdateAvailable -> {
                    val info = apkManager.getCachedApkInfo()
                    ApkPreparationStatus.UpdateAvailable(
                        currentVersion = updateStatus.currentVersion,
                        newVersion = updateStatus.latestRelease.versionName,
                        newSizeMB = (updateStatus.latestRelease.universalApkSize / 1024 / 1024).toInt()
                    )
                }
                is UniversalApkManager.UpdateStatus.Error -> {
                    val info = apkManager.getCachedApkInfo()
                    if (info != null) {
                        // Have cached APK but couldn't check for updates
                        ApkPreparationStatus.Ready(
                            version = info.version,
                            sizeMB = (info.size / 1024 / 1024).toInt()
                        )
                    } else {
                        ApkPreparationStatus.Error(updateStatus.message)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AboutSheet", "Error checking APK status", e)
            ApkPreparationStatus.Error(e.message ?: "Unknown error")
        }
    }
}

/**
 * Download the universal APK with progress tracking.
 */
private suspend fun downloadUniversalApk(
    apkManager: UniversalApkManager,
    onProgress: (Int) -> Unit,
    onResult: (ApkPreparationStatus) -> Unit
) {
    withContext(Dispatchers.IO) {
        val result = apkManager.downloadUniversalApk { progress ->
            onProgress(progress)
        }

        val status = if (result.isSuccess) {
            val file = result.getOrNull()
            if (file != null) {
                val info = apkManager.getCachedApkInfo()
                if (info != null) {
                    ApkPreparationStatus.Ready(
                        version = info.version,
                        sizeMB = (info.size / 1024 / 1024).toInt()
                    )
                } else {
                    ApkPreparationStatus.Error("Download succeeded but metadata not found")
                }
            } else {
                ApkPreparationStatus.Error("Download failed")
            }
        } else {
            val error = result.exceptionOrNull()
            ApkPreparationStatus.Error(error?.message ?: "Download failed")
        }

        withContext(Dispatchers.Main) {
            onResult(status)
        }
    }
}

/**
 * Dialog explaining APK sharing feature before sharing
 */
@Composable
private fun ApkShareExplanationDialog(
    show: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (show) {
        val colorScheme = MaterialTheme.colorScheme

        AlertDialog(
            onDismissRequest = onDismiss,
            icon = {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = null,
                    tint = colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.share_apk_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = colorScheme.onSurface
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.share_apk_explanation),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurface
                    )

                    // Info box with receiver instructions
                    Surface(
                        color = colorScheme.primaryContainer.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                tint = colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = stringResource(R.string.share_apk_receiver_instructions),
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurface.copy(alpha = 0.8f),
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = onConfirm) {
                    Text(
                        text = stringResource(R.string.share_apk_confirm),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(
                        text = stringResource(R.string.cancel),
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

/**
 * Shares the installed APK via standard Android share mechanisms
 */
private suspend fun shareApk(context: android.content.Context) = withContext(Dispatchers.IO) {
    try {
        val apkFiles = ApkSharingUtils.prepareApksForSharing(context) ?: run {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    context.getString(R.string.share_apk_error),
                    Toast.LENGTH_SHORT
                ).show()
            }
            return@withContext
        }

        val uris = apkFiles.map { file ->
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        }

        withContext(Dispatchers.Main) {
            val intent = if (uris.size == 1) {
                // Single APK
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/vnd.android.package-archive"
                    putExtra(Intent.EXTRA_STREAM, uris[0])
                    // Use ClipData for proper URI permission granting on Android 10+
                    clipData = android.content.ClipData.newRawUri("", uris[0])
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                // Multiple split APKs
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "application/vnd.android.package-archive"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    // Use ClipData for proper URI permission granting
                    clipData = android.content.ClipData.newRawUri("", uris[0]).apply {
                        uris.drop(1).forEach { uri ->
                            addItem(android.content.ClipData.Item(uri))
                        }
                    }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }

            val chooser = Intent.createChooser(
                intent,
                context.getString(R.string.share_apk_chooser_title)
            ).apply {
                // Grant read permission on the chooser intent as well
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(chooser)

            // Cleanup after a longer delay to allow share to complete
            // Files remain available during the share process
            // TEMPORARY: Disabled for debugging - check cached APK validity
            // delay(60000) // 60 seconds - enough time for Bluetooth/Nearby Share
            // ApkSharingUtils.cleanupSharedApks(context)
        }
    } catch (e: Exception) {
        Log.e("AboutSheet", "Error sharing APK", e)
        withContext(Dispatchers.Main) {
            Toast.makeText(
                context,
                context.getString(R.string.share_apk_error),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}