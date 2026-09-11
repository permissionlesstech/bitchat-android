package com.bitchat.android.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Bitchat-specific color tokens that do not have a faithful Material 3 semantic role.
 *
 * Standard backgrounds, surfaces, text, outlines, primary/secondary accents, and errors belong
 * to [androidx.compose.material3.MaterialTheme.colorScheme]. Keeping only the extra app semantics
 * here lets Material components inherit correct defaults without losing Bitchat's identity.
 */
@Immutable
data class BitchatPalette(
    // MARK: - Form controls
    /**
     * Resting border for text inputs. A touch darker than the Material outline: the composer is
     * the one surface the user stares at while typing, so it needs to read as a distinct field.
     */
    val inputOutline: Color,
    /** Border for a focused text input. A step brighter, still neutral. */
    val inputOutlineFocused: Color,
    /**
     * Fill for text inputs. A step off the chat background so the field has an edge even where
     * the outline is faint.
     */
    val inputSurface: Color,
    /** Fill for a focused text input. A barely perceptible lift. */
    val inputSurfaceFocused: Color,
    /** Resting disc behind the composer's action glyphs. Neutral grey. */
    val inputButton: Color,

    // MARK: - Extra semantics
    /** Timestamps, placeholders, section labels, disabled states. */
    val textTertiary: Color,
    /** Self, mentions targeting you, unread DMs. */
    val accentOrange: Color,
    /** Nostr reachability. */
    val accentPurple: Color,

    // MARK: - Deterministic peer colors
    /**
     * Saturation/value applied after deriving a peer's stable hue. Swap this when adding a
     * new theme — see [PeerColorStyle] for contrast guidelines.
     */
    val peerColors: PeerColorStyle,
)

val DarkBitchatPalette = BitchatPalette(
    inputOutline = Color(0xFF33333A),
    inputOutlineFocused = Color(0xFF5A5A64),
    inputSurface = Color(0xFF121215),
    inputSurfaceFocused = Color(0xFF1B1B20),
    inputButton = Color(0xFF25252B),
    textTertiary = Color(0xFF71717C),
    accentOrange = Color(0xFFFF9F0A),
    accentPurple = Color(0xFFBF5AF2),
    peerColors = PeerColorStyle.Dark,
)

val LightBitchatPalette = BitchatPalette(
    inputOutline = Color(0xFFD1D1D8),
    inputOutlineFocused = Color(0xFF8E8E9A),
    inputSurface = Color(0xFFFAFAFC),
    inputSurfaceFocused = Color(0xFFF2F2F5),
    inputButton = Color(0xFFE9E9EE),
    textTertiary = Color(0xFF7A7A85),
    accentOrange = Color(0xFFFF9500),
    accentPurple = Color(0xFFAF52DE),
    peerColors = PeerColorStyle.Light,
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
