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
     * Resting border for text inputs. Deliberately a neutral grey rather than the green-tinted
     * Material outline: the composer is the one surface the user stares at while typing.
     */
    val inputOutline: Color,
    /** Border for a focused text input. A step brighter, still neutral. */
    val inputOutlineFocused: Color,
    /**
     * Fill for text inputs. Near-black / near-white and completely untinted, for the same reason
     * as [inputOutline] — and because the composer sits on top of a green-tinted scrim, so any
     * tint of its own compounds into something muddy.
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
    // Neutral M3 tones aligned to the new dark surfaces (surface 0xFF191C19,
    // surfaceVariant/outlineVariant 0xFF43483F, outline 0xFF8D9287).
    inputOutline = Color(0xFF43483F),
    inputOutlineFocused = Color(0xFF8D9287),
    inputSurface = Color(0xFF1E211D),
    inputSurfaceFocused = Color(0xFF262A24),
    inputButton = Color(0xFF2A2E28),
    textTertiary = Color(0xFF8A8F84),
    accentOrange = Color(0xFFFF9F0A),
    accentPurple = Color(0xFFBF5AF2),
    peerColors = PeerColorStyle.Dark,
)

val LightBitchatPalette = BitchatPalette(
    // Neutral M3 tones aligned to the new light surfaces (surface 0xFFF2F6F2,
    // surfaceVariant 0xFFE7EDE7, outline 0xFFCBD6CB).
    inputOutline = Color(0xFFCBD6CB),
    inputOutlineFocused = Color(0xFF8E948E),
    inputSurface = Color(0xFFF2F6F2),
    inputSurfaceFocused = Color(0xFFE7EDE7),
    inputButton = Color(0xFFE0E6E0),
    textTertiary = Color(0xFF6C766C),
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
