package com.bitchat.android.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.bitchat.android.core.designsystem.R

/**
 * The bundled Geist Mono family used throughout the app.
 *
 * Keeping the fonts in the APK preserves offline behavior and guarantees that the design-spec
 * metrics do not depend on which monospace family a device happens to provide.
 */
val BitchatFontFamily = FontFamily(
    Font(R.font.geist_mono_regular, FontWeight.Normal),
    Font(R.font.geist_mono_medium, FontWeight.Medium),
    Font(R.font.geist_mono_semibold, FontWeight.SemiBold),
    Font(R.font.geist_mono_bold, FontWeight.Bold),
)
