package com.bitchat.watch.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

val BitchatWearColorScheme = ColorScheme(
    primary = Color(0xFF32D74B),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF163D1D),
    onPrimaryContainer = Color(0xFFB8F5C1),
    secondary = Color(0xFF0A84FF),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF082E54),
    onSecondaryContainer = Color(0xFFC2E0FF),
    tertiary = Color(0xFFFF9F0A),
    onTertiary = Color.Black,
    background = Color(0xFF000000),
    onBackground = Color(0xFFF5F5F5),
    surfaceContainer = Color(0xFF0E150E),
    surfaceContainerLow = Color(0xFF0B0B0B),
    surfaceContainerHigh = Color(0xFF182118),
    onSurface = Color(0xFFF5F5F5),
    onSurfaceVariant = Color(0xFF9AA69A),
    outline = Color(0xFF2A3A2A),
    outlineVariant = Color(0xFF1C271C),
    error = Color(0xFFFF453A),
    onError = Color.Black,
)

@Composable
fun BitchatWearTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBitchatPalette provides DarkBitchatPalette) {
        MaterialTheme(
            colorScheme = BitchatWearColorScheme,
            typography = BitchatWearTypography,
            content = content
        )
    }
}
