package com.bitchat.watch.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class BitchatPalette(
    val inputOutline: Color,
    val inputOutlineFocused: Color,
    val inputSurface: Color,
    val inputSurfaceFocused: Color,
    val inputButton: Color,
    val textTertiary: Color,
    val accentOrange: Color,
    val accentPurple: Color,
    val peerColors: PeerColorStyle,
)

val DarkBitchatPalette = BitchatPalette(
    inputOutline = Color(0xFF333635),
    inputOutlineFocused = Color(0xFF5A605D),
    inputSurface = Color(0xFF0B0B0B),
    inputSurfaceFocused = Color(0xFF151515),
    inputButton = Color(0xFF1E1E1E),
    textTertiary = Color(0xFF6B776B),
    accentOrange = Color(0xFFFF9F0A),
    accentPurple = Color(0xFFBF5AF2),
    peerColors = PeerColorStyle.Dark,
)

val LocalBitchatPalette = staticCompositionLocalOf { DarkBitchatPalette }

object BitchatMotion {
    const val QUICK_MS = 120
    const val STANDARD_MS = 180
    const val EMPHASIZED_MS = 240
}
