package com.bitchat.android.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.R

/**
 * The bundled Geist Mono family used throughout the app.
 *
 * Keeping the fonts in the APK preserves offline behavior and guarantees that the design-spec
 * metrics do not depend on which monospace family a device happens to provide.
 */
internal val BitchatFontFamily = FontFamily(
    Font(R.font.geist_mono_regular, FontWeight.Normal),
    Font(R.font.geist_mono_medium, FontWeight.Medium),
    Font(R.font.geist_mono_semibold, FontWeight.SemiBold),
    Font(R.font.geist_mono_bold, FontWeight.Bold),
)

/** Exact typography, spacing, and opacity values exported for the chat transcript. */
internal object ChatVisualTokens {
    val MessageBodyFontSize: TextUnit = 14.sp
    val MessageBodyLineHeight: TextUnit = 20.sp
    val SenderFontSize: TextUnit = 14.sp
    val SenderLineHeight: TextUnit = 16.sp
    val SystemActionFontSize: TextUnit = 12.sp
    val SystemActionLineHeight: TextUnit = 16.sp
    val SystemTimeFontSize: TextUnit = 10.sp

    val MessageItemSpacing: Dp = 8.dp
    val SenderTopPadding: Dp = 8.dp
    val SenderToBodySpacing: Dp = 4.dp

    const val SenderSuffixAlpha: Float = 0.60f
    const val HighlightAlpha: Float = 0.20f
    const val MutedTextAlpha: Float = 0.50f

    val MessageBodyStyle = TextStyle(
        fontFamily = BitchatFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = MessageBodyFontSize,
        lineHeight = MessageBodyLineHeight,
    )

    val SenderStyle = TextStyle(
        fontFamily = BitchatFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = SenderFontSize,
        lineHeight = SenderLineHeight,
    )

    val SystemActionStyle = TextStyle(
        fontFamily = BitchatFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = SystemActionFontSize,
        lineHeight = SystemActionLineHeight,
    )
}
