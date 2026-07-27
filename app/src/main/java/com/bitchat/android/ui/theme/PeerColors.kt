package com.bitchat.android.ui.theme

import androidx.compose.ui.graphics.Color
import com.bitchat.android.ui.PeerColorSeed
import kotlin.math.abs

/**
 * Resolve a peer's stable seed using the active Bitchat theme's explicit chroma tokens.
 *
 * The djb2 hash and hue adjustment are byte-identical to the iOS implementation. Orange is
 * avoided because it is reserved for the current user.
 */
fun colorForPeerSeed(seed: PeerColorSeed, palette: BitchatPalette): Color {
    var hash = 5381UL
    for (byte in seed.value.toByteArray()) {
        hash = ((hash shl 5) + hash) + byte.toUByte().toULong()
    }

    var hue = (hash % 360UL).toDouble() / 360.0
    val orange = 30.0 / 360.0
    if (abs(hue - orange) < 0.05) {
        hue = (hue + 0.12) % 1.0
    }

    return Color.hsv(
        hue = (hue * 360).toFloat(),
        saturation = palette.peerColorSaturation,
        value = palette.peerColorValue
    )
}
