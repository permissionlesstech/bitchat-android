package com.bitchat.android.ui

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.feature.settings.R
import com.bitchat.android.core.ui.component.screen.BitchatScreenTopBar
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.net.ArtiTorManager
import com.bitchat.android.net.TorMode
import com.bitchat.android.nostr.NostrProofOfWork
import com.bitchat.android.share.ShareAppSection
import com.bitchat.android.ui.theme.BitchatTheme
import com.bitchat.android.ui.theme.ChatUiMode
import com.bitchat.android.ui.theme.ThemePreference
import com.bitchat.android.ui.theme.BitchatMotion
import com.bitchat.android.ui.theme.LocalBitchatPalette

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
 * About's tabs and their list, under a top bar with a back arrow.
 * [shareSection] renders the APK-sharing rows of the Settings card. The Settings
 * tab offers Debug settings only when [debugEntryAvailable].
 *
 * Takes no window insets of its own: [AboutScreen] passes them in through
 * [modifier].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutContent(
    uiState: AboutUiState,
    onAction: (AboutAction) -> Unit,
    shareSection: @Composable () -> Unit,
    debugEntryAvailable: Boolean,
    initialTab: AboutTab,
    modifier: Modifier = Modifier
) {
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
    // Saveable: Debug is pushed over About from the Settings tab and replaces it rather than
    // covering it, so a remembered tab reset to Info on the way back.
    var selectedTab by rememberSaveable { mutableStateOf(initialTab) }
    var showLanguagePicker by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxWidth()) {
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 72.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // Header Section - App Identity
            item(key = "hero") {
                AboutHero(versionName = uiState.versionName)
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
                    val themePref = uiState.theme
                    val chatUiMode = uiState.chatUiMode
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
                                    onClick = { onAction(AboutAction.ThemeSelected(ThemePreference.System)) },
                                    modifier = Modifier.weight(1f)
                                )
                                ThemeChip(
                                    label = stringResource(R.string.about_light),
                                    selected = themePref.isLight,
                                    onClick = { onAction(AboutAction.ThemeSelected(ThemePreference.Light)) },
                                    modifier = Modifier.weight(1f)
                                )
                                ThemeChip(
                                    label = stringResource(R.string.about_dark),
                                    selected = themePref.isDark,
                                    onClick = { onAction(AboutAction.ThemeSelected(ThemePreference.Dark)) },
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
                                    onClick = { onAction(AboutAction.ChatUiModeSelected(ChatUiMode.Bubbles)) },
                                    modifier = Modifier.weight(1f)
                                )
                                ThemeChip(
                                    label = stringResource(R.string.chat_ui_matrix),
                                    selected = chatUiMode.isMatrix,
                                    onClick = { onAction(AboutAction.ChatUiModeSelected(ChatUiMode.Matrix)) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }

            item(key = "language") {
                val selectedLanguageName = selectedLanguageEndonym(uiState.languages, uiState.selectedLanguageTag)
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
                                selected = isSystemDefaultSelected(uiState.selectedLanguageTag),
                                onClick = {
                                    showLanguagePicker = false
                                    onAction(AboutAction.LanguageSelected(""))
                                },
                            )
                            HorizontalDivider()
                            uiState.languages.forEach { language ->
                                LanguageMenuItem(
                                    label = language.endonym,
                                    selected = isLanguageSelected(language, uiState.selectedLanguageTag),
                                    onClick = {
                                        showLanguagePicker = false
                                        onAction(AboutAction.LanguageSelected(language.languageTag))
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // Settings Section - Unified Card with Toggles
            item(key = "settings") {
                val torStatus = uiState.torStatus
                val torAvailable = uiState.torAvailable

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
                                checked = uiState.backgroundEnabled,
                                onCheckedChange = { enabled -> onAction(AboutAction.BackgroundToggled(enabled)) }
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
                                checked = uiState.liveVoiceEnabled,
                                onCheckedChange = { enabled -> onAction(AboutAction.LiveVoiceToggled(enabled)) }
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
                                checked = uiState.powEnabled,
                                onCheckedChange = { onAction(AboutAction.PowToggled(it)) }
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
                                checked = torSwitchChecked(uiState.torMode),
                                onCheckedChange = { enabled -> onAction(AboutAction.TorToggled(enabled)) },
                                enabled = torAvailable,
                                statusIndicator = if (torStatusShown(uiState.torMode)) {
                                    {
                                        val statusColor = when (torStatusLevel(torStatus)) {
                                            TorStatusLevel.Ready -> colorScheme.primary
                                            TorStatusLevel.Starting -> palette.accentOrange
                                            TorStatusLevel.Down -> colorScheme.error
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

                            shareSection()

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
                val powDifficulty = uiState.powDifficulty

                if (uiState.powEnabled) {
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
                                    onValueChange = { onAction(AboutAction.PowDifficultyChanged(powDifficultyFromSlider(it))) },
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
                                    text = stringResource(powDescriptionRes(powDifficulty)),
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
                val torStatus = uiState.torStatus

                if (torStatusShown(uiState.torMode)) {
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
                                    val statusColor = when (torStatusLevel(torStatus)) {
                                        TorStatusLevel.Ready -> colorScheme.primary
                                        TorStatusLevel.Starting -> palette.accentOrange
                                        TorStatusLevel.Down -> colorScheme.error
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
                                torStatusLogLine(torStatus)?.let { logLine ->
                                    Text(
                                        text = logLine,
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
                    if (debugEntryShown(selectedTab, debugEntryAvailable)) {
                        TextButton(onClick = { onAction(AboutAction.DebugSettingsClicked) }) {
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
        BitchatScreenTopBar(
            onBack = { onAction(AboutAction.BackClicked) },
            title = {},
            modifier = Modifier.align(Alignment.TopCenter),
            backgroundAlpha = topBarAlpha
        )
    }
}

/**
 * About as a full-screen destination: collects [viewModel], routes its events to
 * [onClose] and [onShowDebug], and renders [shareAppSection] in the Settings card.
 * When [onShowDebug] is null the Debug settings entry is not offered.
 */
@Composable
fun AboutRoot(
    shareAppSection: ShareAppSection,
    onClose: () -> Unit,
    onShowDebug: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: AboutViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            AboutEvent.Close -> onClose()
            AboutEvent.NavigateToDebug -> onShowDebug?.invoke()
        }
    }
    AboutScreen(
        uiState = uiState,
        onAction = viewModel::onAction,
        shareSection = { shareAppSection.Content(Modifier) },
        debugEntryAvailable = onShowDebug != null,
        modifier = modifier,
    )
}

/**
 * About's stateless screen. Applies the system-bar insets itself: a route gets none
 * from a sheet host. [initialTab] is the tab a fresh screen opens on (previews and
 * tests); the tab the user picks is kept across a Debug round trip.
 */
@Composable
fun AboutScreen(
    uiState: AboutUiState,
    onAction: (AboutAction) -> Unit,
    shareSection: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    debugEntryAvailable: Boolean = true,
    initialTab: AboutTab = AboutTab.Info,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        AboutContent(
            uiState = uiState,
            onAction = onAction,
            shareSection = shareSection,
            debugEntryAvailable = debugEntryAvailable,
            initialTab = initialTab,
            modifier = Modifier.windowInsetsPadding(
                WindowInsets.systemBars.union(WindowInsets.displayCutout)
            )
        )
    }
}

// --- Previews ---

/** Synthetic state for previews and screen tests: Tor on and bootstrapping, PoW on. */
internal fun previewAboutUiState(): AboutUiState = AboutUiState(
    theme = ThemePreference.System,
    chatUiMode = ChatUiMode.Bubbles,
    languages = listOf(
        AppLanguage(languageTag = "de", endonym = "Deutsch"),
        AppLanguage(languageTag = "fr", endonym = "Français"),
    ),
    selectedLanguageTag = "",
    powEnabled = true,
    powDifficulty = 12,
    backgroundEnabled = true,
    liveVoiceEnabled = true,
    torMode = TorMode.ON,
    torStatus = ArtiTorManager.TorStatus(
        mode = TorMode.ON,
        running = true,
        bootstrapPercent = 60,
        lastLogLine = "Synthetic bootstrap log line",
        state = ArtiTorManager.TorState.BOOTSTRAPPING,
    ),
    torAvailable = true,
    versionName = "1.2.3",
)

@Preview(name = "Info")
@Preview(name = "Info (dark)", uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun AboutInfoPreview() {
    BitchatTheme {
        AboutScreen(uiState = previewAboutUiState(), onAction = {}, shareSection = {})
    }
}

@Preview(name = "Settings, Tor on")
@Preview(name = "Settings, Tor on (dark)", uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun AboutSettingsPreview() {
    BitchatTheme {
        AboutScreen(
            uiState = previewAboutUiState(),
            onAction = {},
            shareSection = {},
            initialTab = AboutTab.Settings,
        )
    }
}
