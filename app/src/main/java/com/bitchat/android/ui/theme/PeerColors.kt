package com.bitchat.android.ui.theme

import androidx.compose.ui.graphics.Color
import com.bitchat.android.ui.PeerIdentity
import kotlin.math.abs

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

    return Color.hsv(
        hue = (hue * 360).toFloat(),
        saturation = palette.peerColorSaturation,
        value = palette.peerColorValue
    )
}
