package com.bitchat.android.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.R
import com.bitchat.android.core.ui.icon.BitChatIcon
import com.bitchat.android.ui.theme.BitchatMotion
import com.bitchat.android.ui.theme.LocalBitchatPalette

/**
 * Building blocks for the redesigned About sheet.
 *
 * Kept in a separate file from [AboutSheet] because the sheet itself is mostly wiring for
 * preferences, whereas these are pure presentation.
 */

/** Horizontal inset shared by every About section, so cards and labels align to one grid. */
internal val AboutHorizontalPadding = 20.dp

/** Card corner radius for grouped rows. */
internal val AboutCardShape = RoundedCornerShape(16.dp)

/** Two top-level views of the sheet. */
enum class AboutTab {
    HowToUse,
    BasicInfo,
}

/**
 * Small uppercase section label, e.g. `SETTINGS`.
 *
 * Uppercasing happens here rather than in the string resource so translators supply natural
 * sentence case and locales without a case distinction are unaffected.
 */
@Composable
internal fun AboutSectionLabel(
    text: String,
    modifier: Modifier = Modifier
) {
    val palette = LocalBitchatPalette.current
    Text(
        text = text.uppercase(),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.8.sp,
        color = palette.textTertiary,
        modifier = modifier.padding(start = AboutHorizontalPadding, top = 24.dp, bottom = 8.dp)
    )
}

/**
 * Centered app identity block: logo, wordmark, tagline, version.
 */
@Composable
internal fun AboutHero(
    versionName: String,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val palette = LocalBitchatPalette.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = BitChatIcon,
            contentDescription = null,
            tint = colorScheme.primary,
            modifier = Modifier.size(64.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.app_name),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 40.sp,
            // Monospace at display size leaves too much air between glyphs; pull it in slightly
            // so the wordmark reads as a single unit.
            letterSpacing = (-0.5).sp,
            color = colorScheme.primary
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = stringResource(R.string.about_tagline),
            fontFamily = FontFamily.Monospace,
            fontSize = 16.sp,
            color = palette.textSecondary
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = stringResource(R.string.version_prefix, versionName),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = palette.textTertiary
        )
    }
}

/**
 * Two-up tab bar with a sliding underline indicator.
 *
 * The indicator animates its offset rather than cross-fading two static bars, which is what
 * makes the switch feel physically connected to the tap.
 */
@Composable
internal fun AboutTabBar(
    selected: AboutTab,
    onSelect: (AboutTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalBitchatPalette.current
    val colorScheme = MaterialTheme.colorScheme
    val density = LocalDensity.current

    var rowWidth by remember { mutableStateOf(0.dp) }
    val tabWidth = rowWidth / 2
    val indicatorOffset by animateDpAsState(
        targetValue = if (selected == AboutTab.HowToUse) 0.dp else tabWidth,
        animationSpec = tween(BitchatMotion.STANDARD_MS, easing = FastOutSlowInEasing),
        label = "aboutTabIndicator"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AboutHorizontalPadding)
            .onSizeChanged { size ->
                rowWidth = with(density) { size.width.toDp() }
            }
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            AboutTabLabel(
                text = stringResource(R.string.about_tab_how_to_use),
                isSelected = selected == AboutTab.HowToUse,
                onClick = { onSelect(AboutTab.HowToUse) },
                modifier = Modifier.weight(1f)
            )
            AboutTabLabel(
                text = stringResource(R.string.about_tab_basic_info),
                isSelected = selected == AboutTab.BasicInfo,
                onClick = { onSelect(AboutTab.BasicInfo) },
                modifier = Modifier.weight(1f)
            )
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider(thickness = 1.dp, color = palette.outlineVariant)
            Box(
                modifier = Modifier
                    // Lambda overload: the offset is animated every frame, and the non-lambda
                    // version would invalidate composition rather than just layout.
                    .offset { IntOffset(x = indicatorOffset.roundToPx(), y = 0) }
                    .width(tabWidth)
                    .height(2.dp)
                    .background(colorScheme.primary)
            )
        }
    }
}

@Composable
private fun AboutTabLabel(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalBitchatPalette.current
    val colorScheme = MaterialTheme.colorScheme

    val color by animateColorAsState(
        targetValue = if (isSelected) colorScheme.primary else palette.textSecondary,
        animationSpec = tween(BitchatMotion.QUICK_MS, easing = FastOutSlowInEasing),
        label = "aboutTabLabelColor"
    )

    Box(
        modifier = modifier
            .height(44.dp)
            .clickable(onClickLabel = text) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text.uppercase(),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.8.sp,
            color = color
        )
    }
}

/** One line of the "How To Use" list: an icon plus a single instruction. */
@Composable
private fun AboutInstructionRow(
    icon: ImageVector,
    text: String
) {
    val colorScheme = MaterialTheme.colorScheme
    val palette = LocalBitchatPalette.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AboutHorizontalPadding, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colorScheme.primary,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(18.dp)
        )
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = palette.textPrimary
        )
    }
}

/**
 * The "How To Use" tab: a short, scannable list of the gestures that are not self-evident.
 */
@Composable
internal fun AboutHowToUseSection(modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.about_how_to_use_heading),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = colorScheme.primary,
            modifier = Modifier.padding(
                start = AboutHorizontalPadding,
                top = 20.dp,
                bottom = 8.dp
            )
        )

        AboutInstructionRow(
            icon = Icons.Filled.AlternateEmail,
            text = stringResource(R.string.about_howto_nickname)
        )
        AboutInstructionRow(
            icon = Icons.Outlined.Public,
            text = stringResource(R.string.about_howto_channels)
        )
        AboutInstructionRow(
            icon = Icons.Filled.Groups,
            text = stringResource(R.string.about_howto_people)
        )
        AboutInstructionRow(
            icon = Icons.Outlined.BookmarkBorder,
            text = stringResource(R.string.about_howto_bookmark)
        )
        AboutInstructionRow(
            icon = Icons.Filled.AlternateEmail,
            text = stringResource(R.string.about_howto_mention)
        )
        AboutInstructionRow(
            icon = Icons.Filled.Terminal,
            text = stringResource(R.string.about_howto_commands)
        )
        AboutInstructionRow(
            icon = Icons.Filled.DeleteForever,
            text = stringResource(R.string.about_howto_panic)
        )
    }
}

/**
 * The six-item capability list from the design.
 *
 * Icons are Material approximations of the designer's custom line art; swap in the exported
 * SVGs when they are available.
 */
@Composable
internal fun AboutFeatureCard(modifier: Modifier = Modifier) {
    val palette = LocalBitchatPalette.current

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AboutHorizontalPadding),
        color = palette.surface,
        shape = AboutCardShape
    ) {
        Column {
            val features = listOf(
                Triple(
                    Icons.Filled.WifiOff,
                    R.string.about_offline_mesh_title,
                    R.string.about_offline_mesh_desc
                ),
                Triple(
                    Icons.Outlined.Lock,
                    R.string.about_e2e_title,
                    R.string.about_e2e_desc
                ),
                Triple(
                    Icons.Outlined.Public,
                    R.string.about_online_geohash_title,
                    R.string.about_online_geohash_desc
                ),
                Triple(
                    Icons.Outlined.VisibilityOff,
                    R.string.about_no_tracking_title,
                    R.string.about_no_tracking_desc
                ),
                Triple(
                    Icons.Filled.Shuffle,
                    R.string.about_ephemeral_title,
                    R.string.about_ephemeral_desc
                ),
                Triple(
                    Icons.Filled.DeleteForever,
                    R.string.about_emergency_title,
                    R.string.about_panic_desc
                ),
            )

            features.forEachIndexed { index, (icon, titleRes, descRes) ->
                if (index > 0) {
                    HorizontalDivider(
                        // Inset to align with the text column, not the icon.
                        modifier = Modifier.padding(start = 54.dp),
                        thickness = 1.dp,
                        color = palette.outlineVariant
                    )
                }
                AboutFeatureRow(
                    icon = icon,
                    title = stringResource(titleRes),
                    subtitle = stringResource(descRes)
                )
            }
        }
    }
}

@Composable
private fun AboutFeatureRow(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    val colorScheme = MaterialTheme.colorScheme
    val palette = LocalBitchatPalette.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colorScheme.primary,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(22.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = palette.textPrimary
            )
            Text(
                text = subtitle,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = palette.textSecondary
            )
        }
    }
}

/**
 * Security-audit warning banner.
 *
 * Deliberately styled as a tinted, outlined card rather than plain red text: it needs to be
 * impossible to skim past, but it is also permanent until the audit lands, so a full-bleed
 * alarm would quickly become wallpaper.
 */
@Composable
internal fun AboutWarningCard(modifier: Modifier = Modifier) {
    val palette = LocalBitchatPalette.current

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AboutHorizontalPadding),
        color = palette.accentRed.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, palette.accentRed.copy(alpha = 0.35f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = palette.accentRed,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = stringResource(R.string.about_warning_title).uppercase(),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    color = palette.accentRed
                )
            }
            Text(
                text = stringResource(R.string.about_warning_body),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = palette.accentRed.copy(alpha = 0.85f)
            )
        }
    }
}

/**
 * Small uppercase pill, e.g. the `RECOMMENDED` badge beside the Tor routing toggle.
 */
@Composable
internal fun BitchatBadge(
    text: String,
    modifier: Modifier = Modifier
) {
    val palette = LocalBitchatPalette.current
    Box(
        modifier = modifier
            .background(palette.accentGreen.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    ) {
        Text(
            text = text.uppercase(),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            color = palette.accentGreen
        )
    }
}

// MARK: - Shared bottom-sheet primitives

/** Horizontal inset for sheet content. Cards inset from here; labels align to the same edge. */
internal val SheetHorizontalPadding = 16.dp

/**
 * Section divider label used across the sheets, e.g. `BOOKMARKED`, `NEARBY`, `ON LOCATION`.
 */
@Composable
internal fun SheetSectionLabel(
    text: String,
    modifier: Modifier = Modifier
) {
    val palette = LocalBitchatPalette.current
    Text(
        text = text.uppercase(),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.8.sp,
        color = palette.textTertiary,
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = SheetHorizontalPadding + 14.dp,
                end = SheetHorizontalPadding,
                top = 20.dp,
                bottom = 6.dp
            )
    )
}

/**
 * Circular tinted badge holding a section's icon, used at the top of the sheets.
 */
@Composable
internal fun SheetHeaderBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val palette = LocalBitchatPalette.current
    Box(
        modifier = modifier
            .size(44.dp)
            .background(palette.surface, androidx.compose.foundation.shape.CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
    }
}

/**
 * Full-width destructive action, e.g. `REMOVE LOCATION ACCESS`.
 *
 * Tinted fill plus an outline rather than a solid red button: the action is legitimate but
 * rarely wanted, and a solid red block would dominate the sheet.
 */
@Composable
internal fun SheetDestructiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDestructive: Boolean = true
) {
    val palette = LocalBitchatPalette.current
    val accent = if (isDestructive) palette.accentRed else palette.accentGreen

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp),
        shape = RoundedCornerShape(10.dp),
        color = accent.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.30f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text.uppercase(),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.8.sp,
                color = accent
            )
        }
    }
}

