package com.bitchat.android.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.feature.location.R
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.ui.theme.BitchatMotion
import com.bitchat.android.ui.theme.LocalBitchatPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Teleport / custom geohash control unified into the same card as channel options.
 */
@Composable
internal fun CustomGeohashRow(
    customGeohash: String,
    onGeohashChange: (String) -> Unit,
    onOpenMap: () -> Unit,
    onTeleport: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val palette = LocalBitchatPalette.current
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val coroutineScope = rememberCoroutineScope()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val isValid = isTeleportEnabled(customGeohash)
    // Typing the last character of a valid geohash arms the button; cross-fading both the label
    // and its container makes that the moment the row confirms the input is usable.
    val teleportColor by animateColorAsState(
        targetValue = if (isValid) colorScheme.primary else palette.textTertiary,
        animationSpec = tween(BitchatMotion.STANDARD_MS, easing = FastOutSlowInEasing),
        label = "teleportLabel"
    )
    val teleportContainer by animateColorAsState(
        targetValue = if (isValid) {
            colorScheme.primary.copy(alpha = 0.16f)
        } else {
            colorScheme.surfaceVariant
        },
        animationSpec = tween(BitchatMotion.STANDARD_MS, easing = FastOutSlowInEasing),
        label = "teleportContainer"
    )
    val teleportInteraction = remember { MutableInteractionSource() }
    val teleportScale = rememberPressScale(teleportInteraction, pressedScale = 0.92f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester)
            .padding(horizontal = ChannelRowHorizontal, vertical = ChannelRowVertical),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(ChannelLeadingSlot),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.PinDrop,
                contentDescription = null,
                tint = colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(ChannelLeadingGutter))

        Text(
            text = stringResource(R.string.hash_symbol),
            fontSize = 14.sp,
            fontFamily = BitchatFontFamily,
            color = palette.textTertiary
        )

        Spacer(modifier = Modifier.width(4.dp))

        BasicTextField(
            value = customGeohash,
            onValueChange = onGeohashChange,
            textStyle = TextStyle(
                fontSize = 14.sp,
                fontFamily = BitchatFontFamily,
                color = colorScheme.primary
            ),
            cursorBrush = SolidColor(colorScheme.primary),
            singleLine = true,
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { focusState ->
                    if (!focusState.isFocused) return@onFocusChanged
                    // Wait until IME insets have landed so the LazyColumn has shrunk; bringIntoView
                    // against the full-height viewport leaves the field tucked under the keyboard.
                    // Re-request once the IME animation settles — early insets are still growing.
                    coroutineScope.launch {
                        withTimeoutOrNull(750) {
                            snapshotFlow { imeInsets.getBottom(density) }
                                .first { it > 0 }
                        }
                        bringIntoViewRequester.bringIntoView()
                        delay(BitchatMotion.STANDARD_MS.toLong())
                        bringIntoViewRequester.bringIntoView()
                    }
                },
            decorationBox = { inner ->
                if (customGeohash.isEmpty()) {
                    Text(
                        text = stringResource(R.string.geohash_placeholder),
                        fontSize = 14.sp,
                        fontFamily = BitchatFontFamily,
                        color = palette.textTertiary
                    )
                }
                inner()
            }
        )

        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .pressScaleClickable(onClick = onOpenMap),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Map,
                contentDescription = stringResource(R.string.cd_open_map),
                tint = colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }

        Surface(
            onClick = onTeleport,
            enabled = isValid,
            shape = RoundedCornerShape(8.dp),
            color = teleportContainer,
            interactionSource = teleportInteraction,
            modifier = Modifier.scale(teleportScale)
        ) {
            Text(
                text = stringResource(R.string.teleport).uppercase(),
                fontSize = 11.sp,
                letterSpacing = 0.8.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = BitchatFontFamily,
                color = teleportColor,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
            )
        }
    }
}
