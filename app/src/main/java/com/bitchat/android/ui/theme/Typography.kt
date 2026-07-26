package com.bitchat.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Base font size for consistent scaling across the app
internal const val BASE_FONT_SIZE = com.bitchat.android.util.AppConstants.UI.BASE_FONT_SIZE_SP // sp - increased from 14sp for better readability

/**
 * Message body style. The generous leading (1.4x) is what gives the redesigned chat surface
 * its readable rhythm; without an explicit lineHeight, Compose falls back to the font's own
 * metrics and lines sit too tightly for long paragraphs.
 */
val MessageBodyTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    fontSize = BASE_FONT_SIZE.sp,
    lineHeight = (BASE_FONT_SIZE + 6).sp
)

/** Sender label above a message group. Single line, never wraps. */
val MessageSenderTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.SemiBold,
    fontSize = BASE_FONT_SIZE.sp,
    lineHeight = (BASE_FONT_SIZE + 5).sp
)

// Typography matching the iOS monospace design - using BASE_FONT_SIZE for consistency
val Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = (BASE_FONT_SIZE + 1).sp,
        lineHeight = (BASE_FONT_SIZE + 7).sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = BASE_FONT_SIZE.sp,
        lineHeight = (BASE_FONT_SIZE + 6).sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = (BASE_FONT_SIZE - 3).sp,
        lineHeight = (BASE_FONT_SIZE + 1).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = (BASE_FONT_SIZE + 3).sp,
        lineHeight = (BASE_FONT_SIZE + 9).sp
    ),
    // Previously unset, which leaked the Roboto default into onboarding + sheet titles.
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = (BASE_FONT_SIZE + 13).sp,
        lineHeight = (BASE_FONT_SIZE + 21).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = (BASE_FONT_SIZE + 5).sp,
        lineHeight = (BASE_FONT_SIZE + 13).sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = (BASE_FONT_SIZE + 1).sp,
        lineHeight = (BASE_FONT_SIZE + 7).sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = (BASE_FONT_SIZE - 1).sp,
        lineHeight = (BASE_FONT_SIZE + 5).sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = (BASE_FONT_SIZE - 2).sp,
        lineHeight = (BASE_FONT_SIZE + 3).sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = (BASE_FONT_SIZE - 4).sp,
        lineHeight = (BASE_FONT_SIZE + 1).sp
    )
)
