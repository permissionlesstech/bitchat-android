package com.bitchat.android.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Centralized, skin-aware semantic accent colors.
 *
 * Historically these meanings (mesh = iOS blue, location = green, "you" = orange, etc.) were
 * hardcoded as `Color(0xFF...)` literals scattered across ~100 call sites. This consolidates
 * them into one provided object so each skin can express the same *meaning* in its own language:
 *
 * - In [AppSkin.MATRIX] they keep the original terminal accent values (iOS parity).
 * - In [AppSkin.EXPRESSIVE] they derive from the (possibly dynamic) Material color scheme so
 *   accents harmonize with the user's wallpaper-based palette.
 */
data class ThemeAccents(
    /** "You" — your own messages, your mentions, the active identity. */
    val self: Color,
    /** Bluetooth mesh transport. */
    val mesh: Color,
    /** Geohash / location channels. */
    val location: Color,
    /** Established end-to-end encryption (lock). */
    val secure: Color,
    /** Tappable links: URLs and geohash references. */
    val link: Color,
    /** Positive / connected status. */
    val success: Color,
    /** Caution / in-progress status. */
    val warning: Color,
    /** Error / disconnected / destructive. */
    val danger: Color,
    /** @mentions of other people. */
    val mention: Color
)

/** Original terminal accent palette (matches the iOS build). */
val MatrixAccents = ThemeAccents(
    self = Color(0xFFFF9500),
    mesh = Color(0xFF007AFF),
    location = Color(0xFF00C851),
    secure = Color(0xFFFF9500),
    link = Color(0xFF007AFF),
    success = Color(0xFF00C851),
    warning = Color(0xFFFF9500),
    danger = Color(0xFFFF3B30),
    mention = Color(0xFFFF9500)
)

/**
 * Derive Expressive accents from the active Material color scheme so they stay coherent with
 * dynamic color. We still keep semantically conventional hues for status (green/amber/red).
 */
fun expressiveAccents(colorScheme: ColorScheme, dark: Boolean): ThemeAccents = ThemeAccents(
    self = colorScheme.tertiary,
    mesh = colorScheme.primary,
    location = colorScheme.secondary,
    secure = colorScheme.tertiary,
    link = colorScheme.primary,
    success = if (dark) Color(0xFF7CDB8E) else Color(0xFF1E7A3C),
    warning = if (dark) Color(0xFFF2C14E) else Color(0xFFB07900),
    danger = colorScheme.error,
    mention = colorScheme.tertiary
)

val LocalThemeAccents = staticCompositionLocalOf { MatrixAccents }
