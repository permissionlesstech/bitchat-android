package com.bitchat.watch.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.CurvedTextStyle
import androidx.wear.compose.material3.Typography
import com.bitchat.watch.R

val BitchatFontFamily = FontFamily(
    Font(R.font.geist_mono_regular, FontWeight.Normal),
    Font(R.font.geist_mono_medium, FontWeight.Medium),
    Font(R.font.geist_mono_semibold, FontWeight.SemiBold),
    Font(R.font.geist_mono_bold, FontWeight.Bold),
)

/**
 * Wear Material's tokens name the device's flex font explicitly, so `defaultFontFamily` alone
 * leaves titles, buttons and dialogs in the system face. Set the family on every role, as the
 * phone theme does, so the whole watch app speaks in the same monospaced voice.
 */
val BitchatWearTypography: Typography = Typography().let { base ->
    fun TextStyle.mono() = copy(fontFamily = BitchatFontFamily)
    fun CurvedTextStyle.mono() = merge(CurvedTextStyle(fontFamily = BitchatFontFamily))
    Typography(
        arcLarge = base.arcLarge.mono(),
        arcMedium = base.arcMedium.mono(),
        arcSmall = base.arcSmall.mono(),
        displayLarge = base.displayLarge.mono(),
        displayMedium = base.displayMedium.mono(),
        displaySmall = base.displaySmall.mono(),
        titleLarge = base.titleLarge.mono(),
        titleMedium = base.titleMedium.mono(),
        titleSmall = base.titleSmall.mono(),
        // Monospace glyphs run wider than the flex face; one step down keeps labels on a line.
        labelLarge = base.labelLarge.mono(),
        labelMedium = base.labelMedium.mono().copy(fontSize = 14.sp),
        labelSmall = base.labelSmall.mono(),
        bodyLarge = base.bodyLarge.mono(),
        bodyMedium = base.bodyMedium.mono(),
        bodySmall = base.bodySmall.mono(),
        bodyExtraSmall = base.bodyExtraSmall.mono(),
        numeralExtraLarge = base.numeralExtraLarge.mono(),
        numeralLarge = base.numeralLarge.mono(),
        numeralMedium = base.numeralMedium.mono(),
        numeralSmall = base.numeralSmall.mono(),
        numeralExtraSmall = base.numeralExtraSmall.mono(),
    )
}

object ChatVisualTokens {
    val MessageBodyStyle = TextStyle(
        fontFamily = BitchatFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 17.sp,
    )

    val SenderStyle = TextStyle(
        fontFamily = BitchatFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 15.sp,
    )

    val SystemActionStyle = TextStyle(
        fontFamily = BitchatFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 15.sp,
    )

    val TimestampStyle = SystemActionStyle.copy(fontSize = 10.sp, lineHeight = 12.sp)
}
