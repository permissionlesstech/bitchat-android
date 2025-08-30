package com.bitchat.android.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.R
import com.bitchat.android.net.TorManager
import com.bitchat.android.net.TorPreferenceManager

data class InfoRow(
    val icon: ImageVector,
    @StringRes val title: Int,
    @StringRes val description: Int
)

private val featuresData = listOf(
    InfoRow(
        Icons.Default.Bluetooth,
        R.string.feature_offline_mesh_title,
        R.string.feature_offline_mesh_desc
    ),
    InfoRow(Icons.Default.Public, R.string.feature_geohash_title, R.string.feature_geohash_desc),
    InfoRow(Icons.Default.Lock, R.string.feature_encryption_title, R.string.feature_e2ee_desc)
)

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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    if (isPresented) {
        ModalBottomSheet(
            modifier = modifier.statusBarsPadding(),
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.background,
            dragHandle = null
        ) {
            AboutSheetContent(onDismiss = onDismiss)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSheetContent(
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lazyListState = rememberLazyListState()
    val isScrolled by remember {
        derivedStateOf {
            lazyListState.firstVisibleItemIndex > 0 || lazyListState.firstVisibleItemScrollOffset > 0
        }
    }
    val topBarAlpha by animateFloatAsState(
        targetValue = if (isScrolled) 0.95f else 0f,
        label = "topBarAlpha"
    )

    Box(modifier = Modifier.fillMaxWidth()) {
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 80.dp, bottom = 20.dp)
        ) {
            item(key = "header") {
                ScreenHeader(modifier = Modifier.padding(horizontal = 24.dp))
            }
            items(featuresData, key = { it.title }) { rowData ->
                FeatureRow(
                    data = rowData,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }

            // Appearance Section
            item(key = "appearance_section") {
                SectionHeader(titleRes = R.string.appearance_title)
                AppearanceSection(
                    context = context,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }

            // Network Section
            item(key = "network_section") {
                SectionHeader(titleRes = R.string.network_title)
                NetworkSection(
                    modifier = Modifier.padding(horizontal = 24.dp),
                    context = context,
                )
            }

            // Emergency Warning Section
            item(key = "warning_section") {
                WarningSection(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp)
                )
            }

            item(key = "footer") {
                Footer(modifier = Modifier.padding(horizontal = 24.dp))
            }
        }

        TopBar(
            alpha = topBarAlpha,
            onDismiss = onDismiss,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}

@Composable
private fun ScreenHeader(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Get version name from package info
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            "1.0.0" // fallback version
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 32.sp
                ),
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = stringResource(R.string.version_prefix, versionName ?: "1.0.0"),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                style = MaterialTheme.typography.bodySmall.copy(
                    baselineShift = BaselineShift(0.2f)
                )
            )
        }
        Text(
            text = stringResource(R.string.about_sheet_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
        )
    }
}

@Composable
private fun SectionHeader(@StringRes titleRes: Int) {
    Text(
        text = stringResource(titleRes).uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .padding(top = 24.dp, bottom = 8.dp)
    )
}

@Composable
private fun FeatureRow(data: InfoRow, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = modifier.padding(vertical = 8.dp)
    ) {
        Icon(
            imageVector = data.icon,
            contentDescription = stringResource(data.title),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(20.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                text = stringResource(data.title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(data.description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
private fun AppearanceSection(
    context: Context,
    modifier: Modifier = Modifier
) {
    val themePref by com.bitchat.android.ui.theme.ThemePreferenceManager.themeFlow.collectAsState()

    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = themePref == com.bitchat.android.ui.theme.ThemePreference.System,
            onClick = {
                com.bitchat.android.ui.theme.ThemePreferenceManager.set(
                    context,
                    com.bitchat.android.ui.theme.ThemePreference.System
                )
            },
            label = {
                Text(
                    stringResource(R.string.theme_system),
                    fontFamily = FontFamily.Monospace
                )
            }
        )
        FilterChip(
            selected = themePref == com.bitchat.android.ui.theme.ThemePreference.Light,
            onClick = {
                com.bitchat.android.ui.theme.ThemePreferenceManager.set(
                    context,
                    com.bitchat.android.ui.theme.ThemePreference.Light
                )
            },
            label = {
                Text(
                    stringResource(R.string.theme_light),
                    fontFamily = FontFamily.Monospace
                )
            }
        )
        FilterChip(
            selected = themePref == com.bitchat.android.ui.theme.ThemePreference.Dark,
            onClick = {
                com.bitchat.android.ui.theme.ThemePreferenceManager.set(
                    context,
                    com.bitchat.android.ui.theme.ThemePreference.Dark
                )
            },
            label = { Text(stringResource(R.string.theme_dark), fontFamily = FontFamily.Monospace) }
        )
    }
}

@Composable
private fun NetworkSection(
    modifier: Modifier = Modifier,
    context: Context
) {
    val torMode =
        remember { mutableStateOf(TorPreferenceManager.get(context)) }
    val torStatus by TorManager.statusFlow.collectAsState()

    val colorScheme = MaterialTheme.colorScheme
    val isDark =
        colorScheme.background.red + colorScheme.background.green + colorScheme.background.blue < 1.5f

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = torMode.value == com.bitchat.android.net.TorMode.OFF,
                onClick = {
                    torMode.value = com.bitchat.android.net.TorMode.OFF
                    TorPreferenceManager.set(context, torMode.value)
                },
                label = {
                    Text(
                        stringResource(R.string.network_tor_off),
                        fontFamily = FontFamily.Monospace
                    )
                }
            )
            FilterChip(
                selected = torMode.value == com.bitchat.android.net.TorMode.ON,
                onClick = {
                    torMode.value = com.bitchat.android.net.TorMode.ON
                    TorPreferenceManager.set(context, torMode.value)
                },
                label = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.network_tor_on),
                            fontFamily = FontFamily.Monospace
                        )
                        val statusColor = when {
                            torStatus.running && torStatus.bootstrapPercent < 100 -> Color(
                                0xFFFF9500
                            )

                            torStatus.running && torStatus.bootstrapPercent >= 100 -> if (isDark) Color(
                                0xFF32D74B
                            ) else Color(0xFF248A3D)

                            else -> Color.Red
                        }
                        Surface(color = statusColor, shape = CircleShape) {
                            Box(Modifier.size(8.dp))
                        }
                    }
                }
            )
        }
        Text(
            text = stringResource(R.string.network_tor_desc),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        // Debug status (optional)
        if (torMode.value == com.bitchat.android.net.TorMode.ON) {
            TorStatusDebug(torStatus)
        }
    }
}

@Composable
fun TorStatusDebug(torStatus: TorManager.TorStatus) {
    val statusText =
        if (torStatus.running) stringResource(R.string.tor_status_running) else stringResource(R.string.tor_status_stopped)
    val colorScheme = MaterialTheme.colorScheme

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
                text = stringResource(
                    R.string.tor_status_label,
                    statusText,
                    torStatus.bootstrapPercent
                ),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = colorScheme.onSurface.copy(alpha = 0.75f)
            )
            val lastLog = torStatus.lastLogLine
            if (lastLog.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.tor_status_last_log, lastLog.take(160)),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}


@Composable
private fun WarningSection(modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    val errorColor = colorScheme.error

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = errorColor.copy(alpha = 0.1f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = stringResource(R.string.warning_title),
                tint = errorColor,
                modifier = Modifier.size(18.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.emergency_delete_title),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = errorColor
                )
                Text(
                    text = stringResource(R.string.emergency_delete_desc),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
private fun Footer(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.footer_text),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(16.dp)) // Extra space for gesture area
    }
}

@Composable
private fun TopBar(
    alpha: Float,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(colorScheme.background.copy(alpha = alpha))
    ) {
        TextButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = stringResource(R.string.done).uppercase(),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = colorScheme.onBackground
            )
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
                    text = stringResource(R.string.channel_password),
                    style = MaterialTheme.typography.titleMedium,
                    color = colorScheme.onSurface
                )
            },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.channel_password_prompt, channelName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = onPasswordChange,
                        label = {
                            Text(
                                stringResource(R.string.channel_password_hint),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
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
                        text = stringResource(R.string.join_button),
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
