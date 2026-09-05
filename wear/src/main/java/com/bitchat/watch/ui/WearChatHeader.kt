package com.bitchat.watch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme

/** The title is outside the scrolling list, so it needs its own physical display bounds. */
@Composable
internal fun WearChatHeader(
    fontSize: Float,
    onClickLabel: String,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    val configuration = LocalConfiguration.current
    val lineHeight = with(LocalDensity.current) { (fontSize * 1.3f).sp.toDp() }
    val rowHeight = maxOf(48.dp, lineHeight)
    val top = 12.dp + (rowHeight - lineHeight) / 2
    BoxWithConstraints(
        Modifier.fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to MaterialTheme.colorScheme.background,
                    0.75f to MaterialTheme.colorScheme.background,
                    1f to Color.Transparent,
                )
            ),
        contentAlignment = Alignment.TopCenter,
    ) {
        val safeWidth =
            if (configuration.isScreenRound) {
                roundBandWidth(
                        maxWidth.value,
                        configuration.screenHeightDp.toFloat(),
                        top.value,
                        (top + lineHeight).value,
                    )
                    .dp - 8.dp
            } else {
                maxWidth - 16.dp
            }
        Row(
            modifier =
                Modifier.padding(top = 12.dp)
                    .width(safeWidth.coerceAtLeast(0.dp))
                    .height(rowHeight)
                    .clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}
