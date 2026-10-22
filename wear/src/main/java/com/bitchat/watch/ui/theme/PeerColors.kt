package com.bitchat.watch.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlin.math.abs

@Immutable
data class PeerColorStyle(
    val saturation: Float,
    val value: Float,
) {
    companion object {
        val Dark = PeerColorStyle(saturation = 0.55f, value = 0.82f)
    }
}

fun colorForPeer(stableKey: String, palette: BitchatPalette): Color {
    var hash = 5381UL
    for (byte in stableKey.toByteArray()) {
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
