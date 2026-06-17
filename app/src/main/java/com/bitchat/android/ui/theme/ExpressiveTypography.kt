package com.bitchat.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * Material 3 Expressive typography for bitchat.
 *
 * Deliberately the opposite of the Matrix skin's uniform monospace: a friendly sans-serif
 * (Roboto / system default) with strong weight contrast and large, confident display sizes.
 * Headlines lean bold; body stays highly legible.
 */

private val ExpressiveFont = FontFamily.Default

private val lineHeightStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None
)

val ExpressiveTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = ExpressiveFont,
        fontWeight = FontWeight.Bold,
        fontSize = 52.sp,
        lineHeight = 58.sp,
        letterSpacing = (-0.5).sp,
        lineHeightStyle = lineHeightStyle
    ),
    displayMedium = TextStyle(
        fontFamily = ExpressiveFont,
        fontWeight = FontWeight.Bold,
        fontSize = 42.sp,
        lineHeight = 48.sp,
        letterSpacing = (-0.25).sp,
        lineHeightStyle = lineHeightStyle
    ),
    displaySmall = TextStyle(
        fontFamily = ExpressiveFont,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        lineHeightStyle = lineHeightStyle
    ),
    headlineLarge = TextStyle(
        fontFamily = ExpressiveFont,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 38.sp,
        lineHeightStyle = lineHeightStyle
    ),
    headlineMedium = TextStyle(
        fontFamily = ExpressiveFont,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 34.sp,
        lineHeightStyle = lineHeightStyle
    ),
    headlineSmall = TextStyle(
        fontFamily = ExpressiveFont,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        lineHeightStyle = lineHeightStyle
    ),
    titleLarge = TextStyle(
        fontFamily = ExpressiveFont,
        fontWeight = FontWeight.Bold,
        fontSize = 21.sp,
        lineHeight = 28.sp,
        lineHeightStyle = lineHeightStyle
    ),
    titleMedium = TextStyle(
        fontFamily = ExpressiveFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp,
        lineHeightStyle = lineHeightStyle
    ),
    titleSmall = TextStyle(
        fontFamily = ExpressiveFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
        lineHeightStyle = lineHeightStyle
    ),
    bodyLarge = TextStyle(
        fontFamily = ExpressiveFont,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
        lineHeightStyle = lineHeightStyle
    ),
    bodyMedium = TextStyle(
        fontFamily = ExpressiveFont,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.25.sp,
        lineHeightStyle = lineHeightStyle
    ),
    bodySmall = TextStyle(
        fontFamily = ExpressiveFont,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.4.sp,
        lineHeightStyle = lineHeightStyle
    ),
    labelLarge = TextStyle(
        fontFamily = ExpressiveFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
        lineHeightStyle = lineHeightStyle
    ),
    labelMedium = TextStyle(
        fontFamily = ExpressiveFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
        lineHeightStyle = lineHeightStyle
    ),
    labelSmall = TextStyle(
        fontFamily = ExpressiveFont,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
        lineHeightStyle = lineHeightStyle
    )
)
