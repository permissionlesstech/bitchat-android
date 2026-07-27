package com.bitchat.android.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic design tokens for the bitchat redesign.
 *
 * Material3's [androidx.compose.material3.ColorScheme] cannot express what this app needs:
 * `onSurface` is the terminal green used for chrome, while message bodies must render in a
 * neutral near-white. Rather than repurposing M3 slots (which would repaint ~40 unrelated
 * files unpredictably), the redesign exposes an explicit palette through
 * [LocalBitchatPalette].
 *
 * Consumers should prefer these tokens over ad-hoc hex literals so accents stay consistent
 * and light/dark pairs are defined in exactly one place.
 */
@Immutable
data class BitchatPalette(
    /** Authoritative dark-mode flag. Prefer this over summing background channels. */
    val isDark: Boolean,

    // MARK: - Surfaces
    /** Screen, header and composer background. True black in dark mode. */
    val background: Color,
    /** Sheets, cards and list rows: one step above [background]. */
    val surface: Color,
    /** Composer pill fill, chips, selected rows: one step above [surface]. */
    val surfaceVariant: Color,
    /** Borders, drawn at full opacity (no `.copy(alpha = ...)` needed). */
    val outline: Color,
    /** Hairline dividers. */
    val outlineVariant: Color,

    // MARK: - Text
    /** Message bodies and row titles. Neutral, not green. */
    val textPrimary: Color,
    /** Subtitles, secondary metadata, system/action messages. */
    val textSecondary: Color,
    /** Timestamps, placeholders, section labels, disabled states. */
    val textTertiary: Color,

    // MARK: - Accents
    /** Geohash channels, bookmark-on, connected/OK states. */
    val accentGreen: Color,
    /** Mesh channel, links, geohash references. */
    val accentBlue: Color,
    /** Self, mentions targeting you, unread DMs. */
    val accentOrange: Color,
    /** Errors, warning card, destructive actions. */
    val accentRed: Color,
    /** Nostr reachability. */
    val accentPurple: Color,
)

val DarkBitchatPalette = BitchatPalette(
    isDark = true,
    // True black. OLED panels switch these pixels off entirely, which is both the terminal
    // aesthetic the app is going for and a real battery win on a screen that is mostly
    // background.
    background = Color(0xFF000000),
    surface = Color(0xFF0E150E),
    surfaceVariant = Color(0xFF182118),
    outline = Color(0xFF2A3A2A),
    outlineVariant = Color(0xFF1C271C),
    textPrimary = Color(0xFFE8EDE8),
    textSecondary = Color(0xFF9AA69A),
    textTertiary = Color(0xFF6B776B),
    accentGreen = Color(0xFF32D74B),
    accentBlue = Color(0xFF0A84FF),
    accentOrange = Color(0xFFFF9F0A),
    accentRed = Color(0xFFFF453A),
    accentPurple = Color(0xFFBF5AF2),
)

val LightBitchatPalette = BitchatPalette(
    isDark = false,
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFF2F6F2),
    surfaceVariant = Color(0xFFE7EDE7),
    outline = Color(0xFFCBD6CB),
    outlineVariant = Color(0xFFDEE6DE),
    textPrimary = Color(0xFF131A13),
    textSecondary = Color(0xFF4C574C),
    textTertiary = Color(0xFF757F75),
    accentGreen = Color(0xFF248A3D),
    accentBlue = Color(0xFF007AFF),
    accentOrange = Color(0xFFFF9500),
    accentRed = Color(0xFFD70015),
    accentPurple = Color(0xFFAF52DE),
)

val LocalBitchatPalette = staticCompositionLocalOf { DarkBitchatPalette }

/**
 * Motion tokens. The redesign leans on short, snappy transitions: long durations read as
 * sluggish on a chat surface where the user is scanning quickly.
 */
object BitchatMotion {
    /** Icon tints, text colors, small fills. */
    const val QUICK_MS = 120

    /** Tab indicators, pill growth, chip reveals. */
    const val STANDARD_MS = 180

    /** Sheet-level fades and scroll-driven top bars. */
    const val EMPHASIZED_MS = 240
}
