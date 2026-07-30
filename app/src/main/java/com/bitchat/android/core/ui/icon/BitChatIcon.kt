package com.bitchat.android.core.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val BitChatIcon: ImageVector
    get() {
        _BitChatIcon?.let { return it }

        return ImageVector.Builder(
            name = "BitChatIcon",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 8f,
            viewportHeight = 8f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(2f, 0f)
                lineTo(6f, 0f)
                lineTo(6f, 1f)
                lineTo(7f, 1f)
                lineTo(7f, 2f)
                lineTo(8f, 2f)
                lineTo(8f, 5f)
                lineTo(7f, 5f)
                lineTo(7f, 6f)
                lineTo(6f, 6f)
                lineTo(6f, 8f)
                lineTo(5f, 8f)
                lineTo(5f, 7f)
                lineTo(3f, 7f)
                lineTo(3f, 6f)
                lineTo(1f, 6f)
                lineTo(1f, 5f)
                lineTo(0f, 5f)
                lineTo(0f, 2f)
                lineTo(1f, 2f)
                lineTo(1f, 1f)
                lineTo(2f, 1f)
                close()
            }
        }.build().also { _BitChatIcon = it }
    }

private var _BitChatIcon: ImageVector? = null
