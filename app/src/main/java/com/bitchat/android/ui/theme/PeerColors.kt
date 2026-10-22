package com.bitchat.android.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.bitchat.android.ui.PeerIdentity
import kotlin.math.abs

/**
 * Theme-specific chroma applied after a peer's stable hue is derived.
 *
 * Hue stays identity-stable across themes (and byte-identical to iOS). Only saturation
 * and value change so peer labels remain readable on each background.
 *
 * Guidelines when adding a future theme:
 * - Dim / dark backgrounds: keep [value] high so colors are not lost against the surface;
 *   prefer muted [saturation] over neon.
 * - Light backgrounds: keep [value] moderate-low so colors are not blinding; avoid
 *   near-full saturation.
 */
@Immutable
data class PeerColorStyle(
    val saturation: Float,
    val value: Float,
) {
    companion object {
        /** Soft pastels that stay bright enough on near-black chat surfaces. */
        val Dark = PeerColorStyle(saturation = 0.55f, value = 0.82f)

        /** Deeper, less saturated tones that stay readable on near-white surfaces. */
        val Light = PeerColorStyle(saturation = 0.70f, value = 0.42f)
    }
}

/**
 * The single identity-to-color boundary used by chat, people sheets, and mentions.
 *
 * The djb2 hash and hue adjustment are byte-identical to the iOS implementation. Orange is
 * avoided because it is reserved for the current user.
 */
fun colorForPeer(identity: PeerIdentity, palette: BitchatPalette): Color {
    var hash = 5381UL
    for (byte in identity.stableKey.toByteArray()) {
        hash = ((hash shl 5) + hash) + byte.toUByte().toULong()
    }

    var hue = (hash % 360UL).toDouble() / 360.0
    val orange = 30.0 / 360.0
    if (abs(hue - orange) < 0.05) {
        hue = (hue + 0.12) % 1.0
    }

    val style = palette.peerColors
    return Color.hsv(
        hue = (hue * 360).toFloat(),
        saturation = style.saturation,
        value = style.value
    )
}
