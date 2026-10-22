package com.bitchat.watch.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Typography
import com.bitchat.android.ui.theme.BitchatFontFamily

val BitchatWearTypography = Typography(
    defaultFontFamily = BitchatFontFamily,
)

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
