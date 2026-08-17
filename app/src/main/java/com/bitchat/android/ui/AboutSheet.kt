package com.bitchat.android.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.R
import com.bitchat.android.core.ui.component.button.CloseButton
import com.bitchat.android.core.ui.component.sheet.LocalSheetDismiss
import com.bitchat.android.core.ui.component.sheet.BitchatBottomSheet
import com.bitchat.android.util.downloadPhaseLabel
import com.bitchat.android.hotspot.HotspotActivity
import com.bitchat.android.net.ArtiTorManager
import com.bitchat.android.net.TorMode
import com.bitchat.android.net.TorPreferenceManager
import com.bitchat.android.nostr.NostrProofOfWork
import com.bitchat.android.nostr.PoWPreferenceManager
import com.bitchat.android.ui.theme.BitchatMotion
import com.bitchat.android.ui.theme.LocalBitchatPalette
import com.bitchat.android.util.ShareableApkVariant
import com.bitchat.android.util.UniversalApkManager

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

    // Cross-fade the chip so switching theme does not read as two separate flashes (the chip
    // recolouring plus the whole app recolouring underneath it).
    val containerColor by animateColorAsState(
        targetValue = if (selected) colorScheme.primary else colorScheme.surfaceVariant,
        animationSpec = tween(BitchatMotion.STANDARD_MS, easing = FastOutSlowInEasing),
        label = "themeChipContainer"
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) Color.White else colorScheme.onSurfaceVariant,
        animationSpec = tween(BitchatMotion.STANDARD_MS, easing = FastOutSlowInEasing),
        label = "themeChipLabel"
    )

    Surface(
        modifier = modifier,
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = containerColor
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontFamily = BitchatFontFamily,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = labelColor
            )
        }
    }
}

@Composable
private fun LanguageSettingsRow(
    selectedLanguageName: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.about_app_language),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = selectedLanguageName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Filled.UnfoldMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun LanguageMenuItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        onClick = onClick,
        trailingIcon = {
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
    )
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
    val palette = LocalBitchatPalette.current
    val interactionSource = remember { MutableInteractionSource() }

    // Colours cross-fade so a row becoming available (Tor finishing bootstrap) eases in rather
    // than popping.
    val iconTint by animateColorAsState(
        targetValue = if (enabled) colorScheme.primary else palette.textTertiary,
        animationSpec = tween(BitchatMotion.STANDARD_MS, easing = FastOutSlowInEasing),
        label = "settingsRowIcon"
    )
    val titleColor by animateColorAsState(
        targetValue = if (enabled) colorScheme.onSurface else palette.textTertiary,
        animationSpec = tween(BitchatMotion.STANDARD_MS, easing = FastOutSlowInEasing),
        label = "settingsRowTitle"
    )
    val subtitleColor by animateColorAsState(
        targetValue = if (enabled) colorScheme.onSurfaceVariant else palette.textTertiary,
        animationSpec = tween(BitchatMotion.STANDARD_MS, easing = FastOutSlowInEasing),
        label = "settingsRowSubtitle"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The whole row toggles, not just the switch: a 14.dp-tall switch is a poor target
            // when there is a full-width row sitting right next to it.
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled
            ) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(22.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

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
                    fontFamily = BitchatFontFamily,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = titleColor
                )
                statusIndicator?.invoke()
            }
            Text(
                text = subtitle,
                fontFamily = BitchatFontFamily,
                fontSize = 12.sp,
                color = subtitleColor,
                lineHeight = 17.sp
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Switch(
            checked = checked,
            onCheckedChange = { if (enabled) onCheckedChange(it) },
            enabled = enabled,
            interactionSource = interactionSource,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = colorScheme.primary,
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
        animationSpec = tween(BitchatMotion.EMPHASIZED_MS, easing = FastOutSlowInEasing),
        label = "topBarAlpha"
    )

    val colorScheme = MaterialTheme.colorScheme
    val palette = LocalBitchatPalette.current
    var selectedTab by remember { mutableStateOf(AboutTab.Info) }
    val supportedLanguages = remember(context) {
        LanguagePreferenceManager.supportedLanguages(context)
    }
    var selectedLanguageTag by remember {
        mutableStateOf(LanguagePreferenceManager.currentLanguageTag())
    }
    var showLanguagePicker by remember { mutableStateOf(false) }

    if (isPresented) {
        BitchatBottomSheet(
            modifier = modifier,
            onDismissRequest = onDismiss,
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 72.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    // Header Section - App Identity
                    item(key = "hero") {
                        AboutHero(versionName = versionName ?: "")
                    }

                    item(key = "tabs") {
                        AboutTabBar(
                            selected = selectedTab,
                            onSelect = { selectedTab = it },
                            modifier = Modifier.padding(top = 24.dp)
                        )
                    }

                    if (selectedTab == AboutTab.Info) {
                        // What the app is, then how to drive it. Both are reference material a
                        // new user reads once, so they belong on the same tab.
                        item(key = "features") {
                            Column {
                                AboutSectionLabel(text = stringResource(R.string.about_section_about))
                                AboutFeatureCard()
                            }
                        }

                        item(key = "how_to_use") {
                            AboutHowToUseSection()
                        }
                    }

                    if (selectedTab == AboutTab.Settings) {
                    // Appearance Section
                    item(key = "appearance") {
                        Column {
                            AboutSectionLabel(text = stringResource(R.string.about_section_theme))
                            val themePref by com.bitchat.android.ui.theme.ThemePreferenceManager.themeFlow.collectAsState()
                            val chatUiMode by com.bitchat.android.ui.theme.ChatUiModeManager.modeFlow.collectAsState()
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = AboutHorizontalPadding),
                                color = colorScheme.surface,
                                shape = AboutCardShape
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
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
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        ThemeChip(
                                            label = stringResource(R.string.chat_ui_bubbles),
                                            selected = chatUiMode.isBubbles,
                                            onClick = { com.bitchat.android.ui.theme.ChatUiModeManager.set(context, com.bitchat.android.ui.theme.ChatUiMode.Bubbles) },
                                            modifier = Modifier.weight(1f)
                                        )
                                        ThemeChip(
                                            label = stringResource(R.string.chat_ui_matrix),
                                            selected = chatUiMode.isMatrix,
                                            onClick = { com.bitchat.android.ui.theme.ChatUiModeManager.set(context, com.bitchat.android.ui.theme.ChatUiMode.Matrix) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item(key = "language") {
                        val selectedLanguageName = supportedLanguages
                            .firstOrNull { it.languageTag == selectedLanguageTag }
                            ?.endonym
                            ?: stringResource(R.string.about_system_default)

                        Column {
                            AboutSectionLabel(text = stringResource(R.string.about_language))
                            BoxWithConstraints(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = AboutHorizontalPadding),
                            ) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = colorScheme.surface,
                                    shape = AboutCardShape,
                                ) {
                                    LanguageSettingsRow(
                                        selectedLanguageName = selectedLanguageName,
                                        onClick = { showLanguagePicker = true },
                                    )
                                }
                                DropdownMenu(
                                    expanded = showLanguagePicker,
                                    onDismissRequest = { showLanguagePicker = false },
                                    modifier = Modifier.width(maxWidth),
                                ) {
                                    LanguageMenuItem(
                                        label = stringResource(R.string.about_system_default),
                                        selected = selectedLanguageTag.isEmpty(),
                                        onClick = {
                                            showLanguagePicker = false
                                            if (selectedLanguageTag.isNotEmpty()) {
                                                selectedLanguageTag = ""
                                                LanguagePreferenceManager.setLanguage("")
                                            }
                                        },
                                    )
                                    HorizontalDivider()
                                    supportedLanguages.forEach { language ->
                                        LanguageMenuItem(
                                            label = language.endonym,
                                            selected = selectedLanguageTag == language.languageTag,
                                            onClick = {
                                                showLanguagePicker = false
                                                if (language.languageTag != selectedLanguageTag) {
                                                    selectedLanguageTag = language.languageTag
                                                    LanguagePreferenceManager.setLanguage(language.languageTag)
                                                }
                                            },
                                        )
                                    }
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
                        var liveVoiceEnabled by remember {
                            mutableStateOf(com.bitchat.android.features.voice.LiveVoicePreferences.isEnabled(context))
                        }
                        val torMode = remember { mutableStateOf(TorPreferenceManager.get(context)) }
                        val torProvider = remember { ArtiTorManager.getInstance() }
                        val torStatus by torProvider.statusFlow.collectAsState()
                        val torAvailable = remember { torProvider.isTorAvailable() }

                        Column {
                            AboutSectionLabel(text = stringResource(R.string.about_section_settings))
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = AboutHorizontalPadding),
                                color = colorScheme.surface,
                                shape = AboutCardShape
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
                                        modifier = Modifier.padding(start = 54.dp),
                                        thickness = 1.dp,
                                        color = colorScheme.outlineVariant
                                    )

                                    SettingsToggleRow(
                                        icon = Icons.Filled.Mic,
                                        title = "Live push-to-talk",
                                        subtitle = "Play voice bursts live on the mesh; voice notes are always sent on release",
                                        checked = liveVoiceEnabled,
                                        onCheckedChange = { enabled ->
                                            liveVoiceEnabled = enabled
                                            com.bitchat.android.features.voice.LiveVoicePreferences.setEnabled(context, enabled)
                                        }
                                    )

                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 54.dp),
                                        thickness = 1.dp,
                                        color = colorScheme.outlineVariant
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
                                        modifier = Modifier.padding(start = 54.dp),
                                        thickness = 1.dp,
                                        color = colorScheme.outlineVariant
                                    )

                                    // Tor Toggle
                                    SettingsToggleRow(
                                        icon = Icons.Filled.Security,
                                        title = stringResource(R.string.about_tor_title),
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
                                                    torStatus.running && torStatus.bootstrapPercent >= 100 -> colorScheme.primary
                                                    torStatus.running -> palette.accentOrange
                                                    else -> colorScheme.error
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
                                    val apkViewModel: ApkDownloadViewModel = viewModel()
                                    val apkUiState by apkViewModel.state.collectAsStateWithLifecycle()
                                    val apkStatus = apkUiState.apkStatus
                                    val releaseStatus = apkUiState.releaseStatus
                                    val downloadProgress = apkUiState.downloadProgress
                                    val shareableApk = when (apkStatus) {
                                        is ApkPreparationStatus.Ready -> apkStatus
                                        is ApkPreparationStatus.Downloading ->
                                            apkStatus.shareableFallback
                                        else -> null
                                    }
                                    val availableUpdate = (releaseStatus as? ApkReleaseStatus.Known)
                                        ?.takeIf { it.isNewerThanSharedApk }

                                    // Handle one-shot effects (navigation, toasts, share intents)
                                    LaunchedEffect(Unit) {
                                        apkViewModel.onEvent(ApkUiEvent.CheckStatus)
                                        apkViewModel.effect.collect { effect ->
                                            when (effect) {
                                                is ApkUiEffect.NavigateToHotspot -> {
                                                    val intent = Intent(context, HotspotActivity::class.java)
                                                    intent.putExtra(HotspotActivity.EXTRA_APK_PATH, effect.apkPath)
                                                    context.startActivity(intent)
                                                }
                                                is ApkUiEffect.ShareApk -> {
                                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                                        type = "application/vnd.android.package-archive"
                                                        putExtra(Intent.EXTRA_STREAM, effect.apkUri)
                                                        clipData = android.content.ClipData.newRawUri("", effect.apkUri)
                                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    }
                                                    val chooser = Intent.createChooser(intent, effect.chooserTitle).apply {
                                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    }
                                                    context.startActivity(chooser)
                                                }
                                                is ApkUiEffect.ShowToast -> {
                                                    Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    }

                                    // Prepare App for Sharing Row
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            // Enabled by the same mapping that decides what the tap
                                            // does, so the row can never look tappable and do
                                            // nothing.
                                            .clickable(
                                                enabled = prepareRowTapAction(
                                                    apkStatus,
                                                    releaseStatus
                                                ) != null
                                            ) {
                                                apkViewModel.onEvent(ApkUiEvent.PrepareRowClicked)
                                            }
                                            .padding(horizontal = 16.dp, vertical = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = if (shareableApk != null) {
                                                Icons.Default.Share
                                            } else {
                                                Icons.Default.CloudDownload
                                            },
                                            contentDescription = null,
                                            tint = colorScheme.primary,
                                            modifier = Modifier.size(22.dp)
                                        )

                                        Spacer(modifier = Modifier.width(14.dp))

                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = if (shareableApk != null) {
                                                        stringResource(R.string.prepare_apk_ready_title)
                                                    } else {
                                                        stringResource(R.string.prepare_apk_title)
                                                    },
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Medium,
                                                    color = colorScheme.onSurface
                                                )
                                                if (availableUpdate != null) {
                                                    TooltipBox(
                                                        positionProvider = TooltipDefaults
                                                            .rememberTooltipPositionProvider(),
                                                        tooltip = {
                                                            PlainTooltip {
                                                                Text(
                                                                    stringResource(
                                                                        R.string.prepare_apk_update_available,
                                                                        availableUpdate.version
                                                                    )
                                                                )
                                                            }
                                                        },
                                                        state = rememberTooltipState()
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Warning,
                                                            contentDescription = stringResource(
                                                                R.string.prepare_apk_update_warning
                                                            ),
                                                            tint = colorScheme.tertiary,
                                                            modifier = Modifier
                                                                .padding(start = 6.dp)
                                                                .size(18.dp)
                                                                .clickable {
                                                                    apkViewModel.onEvent(
                                                                        ApkUiEvent.DownloadUniversalClicked
                                                                    )
                                                                }
                                                        )
                                                    }
                                                }
                                            }
                                            Text(
                                                text = when (val status = apkStatus) {
                                                    is ApkPreparationStatus.Loading -> stringResource(R.string.checking)
                                                    is ApkPreparationStatus.NotDownloaded ->
                                                        stringResource(
                                                            R.string.prepare_apk_status_not_downloaded
                                                        )
                                                    is ApkPreparationStatus.Ready -> {
                                                        val source = when {
                                                            status.source == UniversalApkManager.ApkSource.DOWNLOADED ->
                                                                stringResource(R.string.prepare_apk_source_downloaded)
                                                            status.variant == ShareableApkVariant.ARM64 ->
                                                                stringResource(R.string.prepare_apk_source_installed_arm64)
                                                            else ->
                                                                stringResource(R.string.prepare_apk_source_installed)
                                                        }
                                                        stringResource(
                                                            R.string.prepare_apk_ready_detail,
                                                            status.version,
                                                            status.sizeMB,
                                                            source
                                                        )
                                                    }
                                                    is ApkPreparationStatus.Downloading ->
                                                        // Only the transfer has a percentage worth
                                                        // showing; the other phases are named
                                                        // instead of pretending to be at 0%.
                                                        if (status.phase.hasMeasurableProgress) {
                                                            stringResource(R.string.prepare_apk_status_downloading, downloadProgress)
                                                        } else {
                                                            stringResource(downloadPhaseLabel(status.phase))
                                                        }
                                                    is ApkPreparationStatus.Resumable ->
                                                        stringResource(
                                                            R.string.prepare_apk_status_resumable,
                                                            context.resolveApkFailureMessage(
                                                                status.failure
                                                            ),
                                                            status.progressPercent
                                                        )
                                                    is ApkPreparationStatus.Error ->
                                                        context.resolveApkFailureMessage(
                                                            status.failure
                                                        )
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = when (apkStatus) {
                                                    is ApkPreparationStatus.Error -> colorScheme.error
                                                    is ApkPreparationStatus.Resumable -> colorScheme.primary
                                                    else -> colorScheme.onSurface.copy(alpha = 0.6f)
                                                },
                                                lineHeight = 16.sp
                                            )

                                            // Progress lives in the column, not the trailing slot,
                                            // which leaves that slot free for a single control.
                                            ApkDownloadProgressBar(
                                                status = apkStatus,
                                                progressPercent = downloadProgress
                                            )
                                        }

                                        // One control, one width, in every state. The progress
                                        // readout moved into the column above, so nothing else
                                        // competes for this slot.
                                        when (apkStatus) {
                                            is ApkPreparationStatus.Downloading ->
                                                ApkPrepareRowIconButton(
                                                    icon = Icons.Default.Close,
                                                    description = stringResource(
                                                        R.string.prepare_apk_stop
                                                    ),
                                                    onClick = {
                                                        apkViewModel.onEvent(
                                                            ApkUiEvent.CancelDownload
                                                        )
                                                    }
                                                )
                                            is ApkPreparationStatus.Ready -> {
                                                if (
                                                    apkStatus.source ==
                                                    UniversalApkManager.ApkSource.INSTALLED
                                                ) {
                                                    ApkPrepareRowIconButton(
                                                        icon = Icons.Default.CloudDownload,
                                                        description = stringResource(
                                                            R.string.prepare_apk_get_universal
                                                        ),
                                                        onClick = {
                                                            apkViewModel.onEvent(
                                                                ApkUiEvent.DownloadUniversalClicked
                                                            )
                                                        },
                                                        tint = colorScheme.primary
                                                    )
                                                } else if (
                                                    apkStatus.source ==
                                                    UniversalApkManager.ApkSource.DOWNLOADED
                                                ) {
                                                    ApkPrepareRowIconButton(
                                                        icon = Icons.Default.Delete,
                                                        description = stringResource(
                                                            R.string.prepare_apk_button_delete
                                                        ),
                                                        onClick = {
                                                            apkViewModel.onEvent(
                                                                ApkUiEvent.DeleteClicked
                                                            )
                                                        },
                                                        tint = colorScheme.error
                                                    )
                                                }
                                            }
                                            is ApkPreparationStatus.Resumable,
                                            is ApkPreparationStatus.Error ->
                                                ApkPrepareRowIconButton(
                                                    icon = Icons.Default.Refresh,
                                                    description = stringResource(
                                                        R.string.prepare_apk_retry
                                                    ),
                                                    onClick = {
                                                        apkViewModel.onEvent(
                                                            ApkUiEvent.PrepareRowClicked
                                                        )
                                                    },
                                                    tint = colorScheme.primary
                                                )
                                            else -> {}
                                        }
                                    }

                                    // Prepare Dialog
                                    if (apkUiState.showPrepareDialog) {
                                        AlertDialog(
                                            onDismissRequest = { apkViewModel.onEvent(ApkUiEvent.DismissPrepareDialog) },
                                            title = {
                                                Text(
                                                    text = stringResource(
                                                        if (availableUpdate != null) {
                                                            R.string.prepare_apk_update_dialog_title
                                                        } else {
                                                            R.string.prepare_apk_dialog_title
                                                        }
                                                    ),
                                                    style = MaterialTheme.typography.titleLarge
                                                )
                                            },
                                            text = {
                                                Text(
                                                    text = if (availableUpdate != null) {
                                                        stringResource(
                                                            R.string.prepare_apk_update_dialog_message,
                                                            availableUpdate.version,
                                                            availableUpdate.sizeMB
                                                        )
                                                    } else {
                                                        stringResource(
                                                            R.string.prepare_apk_dialog_message_unknown_size
                                                        )
                                                    },
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                            },
                                            confirmButton = {
                                                Button(onClick = {
                                                    apkViewModel.onEvent(ApkUiEvent.ConfirmDownload)
                                                }) {
                                                    Text(stringResource(R.string.prepare_apk_dialog_confirm))
                                                }
                                            },
                                            dismissButton = {
                                                TextButton(onClick = { apkViewModel.onEvent(ApkUiEvent.DismissPrepareDialog) }) {
                                                    Text(stringResource(R.string.cancel))
                                                }
                                            },
                                            containerColor = colorScheme.surface
                                        )
                                    }

                                    // Delete Dialog
                                    if (apkUiState.showDeleteDialog) {
                                        val sizeMB = (apkStatus as? ApkPreparationStatus.Ready)?.sizeMB ?: 0
                                        AlertDialog(
                                            onDismissRequest = { apkViewModel.onEvent(ApkUiEvent.DismissDeleteDialog) },
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
                                                        apkViewModel.onEvent(ApkUiEvent.ConfirmDelete)
                                                    },
                                                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                                        containerColor = colorScheme.error
                                                    )
                                                ) {
                                                    Text(
                                                        stringResource(
                                                            R.string.prepare_apk_button_delete
                                                        )
                                                    )
                                                }
                                            },
                                            dismissButton = {
                                                TextButton(onClick = { apkViewModel.onEvent(ApkUiEvent.DismissDeleteDialog) }) {
                                                    Text(stringResource(R.string.cancel))
                                                }
                                            },
                                            containerColor = colorScheme.surface
                                        )
                                    }

                                    // A GitHub update is optional. Keep sharing visible while the
                                    // replacement downloads or while metadata refreshes.
                                    val canShareAPK = shareableApk != null

                                    AnimatedVisibility(
                                        visible = canShareAPK,
                                        enter = fadeIn() + expandVertically(),
                                        exit = fadeOut() + shrinkVertically()
                                    ) {
                                        Column {
                                            HorizontalDivider(
                                                modifier = Modifier.padding(start = 56.dp),
                                                color = colorScheme.outline.copy(alpha = 0.12f)
                                            )

                                            // === Share via Hotspot Row ===
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        apkViewModel.onEvent(ApkUiEvent.HotspotShareClicked)
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
                                                text = stringResource(R.string.hotspot_share_via_subtitle),
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

                                            HorizontalDivider(
                                        modifier = Modifier.padding(start = 56.dp),
                                        color = colorScheme.outline.copy(alpha = 0.12f)
                                    )

                                    // === Share via Bluetooth/Email Row (Fallback) ===
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { apkViewModel.onEvent(ApkUiEvent.AppShareClicked) }
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
                                                text = stringResource(R.string.hotspot_share_other_subtitle),
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
                                                show = apkUiState.showShareApkDialog,
                                                onConfirm = {
                                                    apkViewModel.onEvent(ApkUiEvent.ConfirmAppShare)
                                                },
                                                onDismiss = { apkViewModel.onEvent(ApkUiEvent.DismissShareDialog) }
                                            )
                                        }
                                    }

                                }
                            }

                            // Tor unavailable hint
                            if (!torAvailable) {
                                Text(
                                    text = stringResource(R.string.tor_not_available_in_this_build),
                                    fontSize = 12.sp,
                                    fontFamily = BitchatFontFamily,
                                    color = palette.textTertiary,
                                    modifier = Modifier.padding(
                                        start = AboutHorizontalPadding + 16.dp,
                                        top = 8.dp
                                    )
                                )
                            }
                        }
                    }

                    // PoW Difficulty Slider (when enabled)
                    item(key = "pow_slider") {
                        val powEnabled by PoWPreferenceManager.powEnabled.collectAsState()
                        val powDifficulty by PoWPreferenceManager.powDifficulty.collectAsState()

                        if (powEnabled) {
                            Column(modifier = Modifier.padding(top = 12.dp)) {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = AboutHorizontalPadding),
                                    color = colorScheme.surface,
                                    shape = AboutCardShape
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
                                                text = stringResource(R.string.about_difficulty),
                                                fontFamily = BitchatFontFamily,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = colorScheme.onSurface
                                            )
                                            AnimatedCountLabel(
                                                count = powDifficulty,
                                                text = stringResource(
                                                    R.string.about_difficulty_value,
                                                    powDifficulty,
                                                    NostrProofOfWork.estimateMiningTime(powDifficulty)
                                                ),
                                                fontFamily = BitchatFontFamily,
                                                fontSize = 12.sp,
                                                color = colorScheme.onSurfaceVariant
                                            )
                                        }

                                        Slider(
                                            value = powDifficulty.toFloat(),
                                            onValueChange = { PoWPreferenceManager.setPowDifficulty(it.toInt()) },
                                            valueRange = 0f..32f,
                                            steps = 31,
                                            colors = SliderDefaults.colors(
                                                thumbColor = colorScheme.primary,
                                                activeTrackColor = colorScheme.primary,
                                                inactiveTrackColor = colorScheme.surfaceVariant
                                            )
                                        )

                                        AnimatedCountLabel(
                                            count = powDifficulty,
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
                                            fontFamily = BitchatFontFamily,
                                            color = palette.textTertiary
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
                            Column(modifier = Modifier.padding(top = 12.dp)) {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = AboutHorizontalPadding),
                                    color = colorScheme.surface,
                                    shape = AboutCardShape
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
                                                torStatus.running && torStatus.bootstrapPercent >= 100 -> colorScheme.primary
                                                torStatus.running -> palette.accentOrange
                                                else -> colorScheme.error
                                            }
                                            Surface(color = statusColor, shape = CircleShape, modifier = Modifier.size(10.dp)) {}
                                            Text(
                                                text = if (torStatus.running) {
                                                    stringResource(R.string.about_tor_connected, torStatus.bootstrapPercent)
                                                } else {
                                                    stringResource(R.string.about_tor_disconnected)
                                                },
                                                fontFamily = BitchatFontFamily,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = colorScheme.onSurface
                                            )
                                        }
                                        if (torStatus.lastLogLine.isNotEmpty()) {
                                            Text(
                                                text = torStatus.lastLogLine.take(120),
                                                fontSize = 11.sp,
                                                fontFamily = BitchatFontFamily,
                                                color = palette.textTertiary,
                                                maxLines = 2
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    } // end Settings tab

                    // Footer
                    item(key = "footer") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = AboutHorizontalPadding,
                                    end = AboutHorizontalPadding,
                                    top = 24.dp
                                ),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (selectedTab == AboutTab.Settings && onShowDebug != null) {
                                TextButton(onClick = onShowDebug) {
                                    Text(
                                        text = stringResource(R.string.about_debug_settings),
                                        fontSize = 13.sp,
                                        fontFamily = BitchatFontFamily,
                                        color = colorScheme.primary
                                    )
                                }
                            }
                            Text(
                                text = stringResource(R.string.about_footer),
                                fontSize = 11.sp,
                                fontFamily = BitchatFontFamily,
                                color = palette.textTertiary
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
                        .background(colorScheme.background.copy(alpha = topBarAlpha))
                ) {
                    val dismiss = LocalSheetDismiss.current
                    CloseButton(
                        onClick = { dismiss?.invoke() ?: onDismiss() },
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
                            fontFamily = BitchatFontFamily
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
