package com.bitchat.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.feature.chat.R
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.ui.theme.BitchatMotion
import com.bitchat.android.ui.theme.LocalBitchatPalette

@Composable
internal fun meshConnectionDescription(connection: MeshConnectionKind): String = stringResource(
    when (connection) {
        MeshConnectionKind.WifiAware -> R.string.cd_direct_wifi_aware
        MeshConnectionKind.Bluetooth -> R.string.cd_direct_bluetooth
        MeshConnectionKind.Routed -> R.string.cd_routed_mesh
    }
)

/**
 * Reusable unread badge component for both channels and private messages
 */
@Composable
internal fun UnreadBadge(
    count: Int,
    colorScheme: ColorScheme,
    modifier: Modifier = Modifier
) {
    val palette = LocalBitchatPalette.current
    // Scale/fade in and out so a badge appearing while the sheet is open is noticed, and one
    // clearing does not just blink away.
    AnimatedVisibility(
        visible = count > 0,
        enter = fadeIn(tween(BitchatMotion.STANDARD_MS)) +
            scaleIn(
                initialScale = 0.5f,
                animationSpec = tween(BitchatMotion.STANDARD_MS, easing = FastOutSlowInEasing)
            ),
        exit = fadeOut(tween(BitchatMotion.QUICK_MS)) +
            scaleOut(
                targetScale = 0.5f,
                animationSpec = tween(BitchatMotion.QUICK_MS, easing = FastOutSlowInEasing)
            ),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .background(
                    color = palette.accentOrange,
                    shape = RoundedCornerShape(10.dp)
                )
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp),
            contentAlignment = Alignment.Center
        ) {
            AnimatedCountLabel(
                count = count,
                text = if (count > 99) "99+" else count.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = BitchatFontFamily,
                color = Color.Black
            )
        }
    }
}
