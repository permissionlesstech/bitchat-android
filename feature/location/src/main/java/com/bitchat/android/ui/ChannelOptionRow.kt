package com.bitchat.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.feature.location.R
import com.bitchat.android.geohash.GeohashChannelLevel
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.ui.theme.LocalBitchatPalette

/**
 * Leading column width matching settings rows: 22.dp glyph + 16.dp gutter before title text.
 * Selection dots and row icons sit in this column so every option lines up with About settings.
 */
internal val ChannelLeadingSlot = SheetRowLeadingSlot
internal val ChannelLeadingGutter = SheetRowLeadingGutter
internal val ChannelRowHorizontal = SheetRowHorizontal
internal val ChannelRowVertical = SheetRowVertical
internal val ChannelDividerInset = SheetRowDividerInset
/** 2× the previous 6.dp selected indicator; sits centered in [ChannelLeadingSlot]. */
internal val ChannelSelectedDot = SheetRowSelectedDot

/**
 * Single channel option — settings-row geometry: 22.dp leading slot, title + subtitle, trailing.
 * Selected state is a 12.dp green dot centered in the leading slot (icon-sized footprint).
 */
@Composable
internal fun ChannelOptionRow(
    title: String,
    countText: String?,
    subtitle: String,
    isSelected: Boolean,
    participantCount: Int,
    titleColor: Color? = null,
    titleBold: Boolean = false,
    leadingIcon: ImageVector? = null,
    leadingIconRes: Int? = null,
    trailingContent: (@Composable (() -> Unit))? = null,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = ChannelRowHorizontal, vertical = ChannelRowVertical),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(ChannelLeadingSlot),
            contentAlignment = Alignment.Center
        ) {
            when {
                isSelected -> {
                    Box(
                        modifier = Modifier
                            .size(ChannelSelectedDot)
                            .background(colorScheme.primary, CircleShape)
                    )
                }
                leadingIcon != null -> {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        tint = colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                leadingIconRes != null -> {
                    Icon(
                        painter = painterResource(leadingIconRes),
                        contentDescription = null,
                        tint = colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(ChannelLeadingGutter))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontFamily = BitchatFontFamily,
                    fontWeight = if (titleBold) FontWeight.SemiBold else FontWeight.Medium,
                    color = titleColor ?: colorScheme.onSurface
                )
                countText?.let { count ->
                    AnimatedCountLabel(
                        count = participantCount,
                        text = count,
                        fontSize = 11.sp,
                        fontFamily = BitchatFontFamily,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = subtitle,
                fontSize = 12.sp,
                fontFamily = BitchatFontFamily,
                lineHeight = 17.sp,
                color = colorScheme.onSurfaceVariant
            )
        }

        if (trailingContent != null) {
            Spacer(modifier = Modifier.width(8.dp))
            trailingContent()
        }
    }
}

@Composable
internal fun ChannelBookmarkButton(
    bookmarked: Boolean,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(36.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(
                if (bookmarked) {
                    R.drawable.ic_spec_bookmark_filled
                } else {
                    R.drawable.ic_spec_bookmark_outline
                }
            ),
            contentDescription = stringResource(
                if (bookmarked) R.string.cd_remove_bookmark else R.string.cd_add_bookmark
            ),
            tint = if (bookmarked) colorScheme.primary else colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
internal fun ChannelLoadingRow() {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ChannelRowHorizontal, vertical = ChannelRowVertical),
        horizontalArrangement = Arrangement.spacedBy(ChannelLeadingGutter),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(ChannelLeadingSlot),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = stringResource(R.string.finding_nearby_channels),
            fontSize = 12.sp,
            fontFamily = BitchatFontFamily,
            color = colorScheme.onSurfaceVariant
        )
    }
}

/** Settings-toggle row geometry (icon + title/subtitle + switch), local copy for this sheet. */
@Composable
internal fun ChannelSettingsToggleRow(
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ChannelRowHorizontal, vertical = ChannelRowVertical),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) colorScheme.primary else palette.textTertiary,
            modifier = Modifier.size(ChannelLeadingSlot)
        )
        Spacer(modifier = Modifier.width(ChannelLeadingGutter))
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
                    color = if (enabled) colorScheme.onSurface else palette.textTertiary
                )
                statusIndicator?.invoke()
            }
            Text(
                text = subtitle,
                fontFamily = BitchatFontFamily,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = if (enabled) colorScheme.onSurfaceVariant else palette.textTertiary
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = { if (enabled) onCheckedChange(it) },
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = colorScheme.primary,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = colorScheme.surfaceVariant
            )
        )
    }
}

/** L10: the `people_count` label of [title]; "?" in place of 0 when the count is unknown. */
@Composable
internal fun peopleCountLabel(title: ChannelTitle): String {
    val ctx = LocalContext.current
    return channelCountText(title) { n ->
        ctx.resources.getQuantityString(R.plurals.people_count, n, n)
    }
}

/**
 * L10: a nearby row's title. "Building" is a hard-coded literal, unreachable because L1 drops
 * BUILDING channels (Q-L14).
 */
@Composable
internal fun channelLevelName(level: GeohashChannelLevel): String = when (level) {
    GeohashChannelLevel.BUILDING -> "Building"
    GeohashChannelLevel.BLOCK -> stringResource(R.string.location_level_block)
    GeohashChannelLevel.NEIGHBORHOOD -> stringResource(R.string.location_level_neighborhood)
    GeohashChannelLevel.CITY -> stringResource(R.string.location_level_city)
    GeohashChannelLevel.PROVINCE -> stringResource(R.string.location_level_province)
    GeohashChannelLevel.REGION -> stringResource(R.string.location_level_region)
}
