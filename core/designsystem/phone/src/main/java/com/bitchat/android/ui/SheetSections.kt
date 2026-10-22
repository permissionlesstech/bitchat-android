package com.bitchat.android.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.ui.theme.LocalBitchatPalette

/**
 * Grid, labels and row primitives shared by About and the location, network and
 * people sheets. They began as About's building blocks, hence the names.
 */

/** Horizontal inset shared by every About section, so cards and labels align to one grid. */
val AboutHorizontalPadding = 20.dp

/** Card corner radius for grouped rows. */
val AboutCardShape = RoundedCornerShape(16.dp)

/** Leading icon column in settings-style sheet rows. */
val SheetRowLeadingSlot = 22.dp
val SheetRowLeadingGutter = 16.dp
val SheetRowHorizontal = 16.dp
val SheetRowVertical = 13.dp

/**
 * Exact height of a people-list row.
 *
 * Fixed rather than derived from content: these lists reorder themselves constantly, and a row
 * whose height depends on its content makes the whole card change height every time the order
 * changes. Equals the leading glyph plus [SheetRowVertical] above and below.
 */
internal val SheetRowHeight = SheetRowLeadingSlot + SheetRowVertical * 2
val SheetRowDividerInset = SheetRowHorizontal + SheetRowLeadingSlot + SheetRowLeadingGutter
/** Selection indicator sized for [SheetRowLeadingSlot]. */
val SheetRowSelectedDot = 12.dp

/**
 * Small uppercase section label, e.g. `SETTINGS`.
 *
 * Uppercasing happens here rather than in the string resource so translators supply natural
 * sentence case and locales without a case distinction are unaffected.
 */
@Composable
fun AboutSectionLabel(
    text: String,
    modifier: Modifier = Modifier
) {
    val palette = LocalBitchatPalette.current
    Text(
        text = text.uppercase(),
        fontFamily = BitchatFontFamily,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.8.sp,
        color = palette.textTertiary,
        modifier = modifier.padding(start = AboutHorizontalPadding, top = 24.dp, bottom = 8.dp)
    )
}

/**
 * Icon + title on one line, optional short subtitle beneath. Used by location / network sheets.
 */
@Composable
fun SheetIconSectionHeader(
    @DrawableRes iconRes: Int,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AboutHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = title,
                fontSize = 17.sp,
                fontFamily = BitchatFontFamily,
                fontWeight = FontWeight.SemiBold,
                color = colorScheme.primary
            )
        }
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                fontFamily = BitchatFontFamily,
                color = colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Inset divider used inside grouped sheet cards (aligns with text column after the leading slot). */
@Composable
fun SheetCardDivider() {
    val colorScheme = MaterialTheme.colorScheme
    HorizontalDivider(
        modifier = Modifier.padding(start = SheetRowDividerInset),
        thickness = 1.dp,
        color = colorScheme.outlineVariant
    )
}

/**
 * Small uppercase pill, e.g. the `RECOMMENDED` badge beside the Tor routing toggle.
 */
@Composable
fun BitchatBadge(
    text: String,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .background(colorScheme.primary.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    ) {
        Text(
            text = text.uppercase(),
            fontFamily = BitchatFontFamily,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            color = colorScheme.primary
        )
    }
}

// MARK: - Shared bottom-sheet primitives

/**
 * Full-width destructive action, e.g. `REMOVE LOCATION ACCESS`.
 *
 * Tinted fill plus an outline rather than a solid red button: the action is legitimate but
 * rarely wanted, and a solid red block would dominate the sheet.
 */
@Composable
fun SheetDestructiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDestructive: Boolean = true
) {
    val colorScheme = MaterialTheme.colorScheme
    val accent = if (isDestructive) colorScheme.error else colorScheme.primary

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
                fontFamily = BitchatFontFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.8.sp,
                color = accent
            )
        }
    }
}
