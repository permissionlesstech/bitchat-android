package com.bitchat.android.ui.theme

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsetsController
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

// Standard UI semantics live in Material so stock components and custom Bitchat composables
// share one source of truth. LocalBitchatPalette below only supplies app-specific extra colors.
// Fixed fallback schemes for devices below Android 12 (no Material You dynamic color).
// Neutral Material 3 surfaces with a retained green-family accent so the app keeps its identity
// without the old terminal green-on-black surface tint.
internal val DarkBitchatColorScheme = darkColorScheme(
    primary = Color(0xFF7CDC8A),
    onPrimary = Color(0xFF00390F),
    primaryContainer = Color(0xFF1C4A28),
    onPrimaryContainer = Color(0xFFB8F5C1),
    secondary = Color(0xFF9CCBFF),
    onSecondary = Color(0xFF00325A),
    secondaryContainer = Color(0xFF1B4870),
    onSecondaryContainer = Color(0xFFD3E4FF),
    tertiary = DarkBitchatPalette.accentOrange,
    onTertiary = Color.Black,
    background = Color(0xFF111311),
    onBackground = Color(0xFFE3E3DE),
    surface = Color(0xFF191C19),
    onSurface = Color(0xFFE3E3DE),
    surfaceVariant = Color(0xFF43483F),
    onSurfaceVariant = Color(0xFFC3C8BC),
    outline = Color(0xFF8D9287),
    outlineVariant = Color(0xFF43483F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

internal val LightBitchatColorScheme = lightColorScheme(
    primary = Color(0xFF248A3D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5F1D8),
    onPrimaryContainer = Color(0xFF0A3212),
    secondary = Color(0xFF007AFF),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6E9FF),
    onSecondaryContainer = Color(0xFF002C5C),
    tertiary = LightBitchatPalette.accentOrange,
    onTertiary = Color.Black,
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF131A13),
    surface = Color(0xFFF2F6F2),
    onSurface = Color(0xFF131A13),
    surfaceVariant = Color(0xFFE7EDE7),
    onSurfaceVariant = Color(0xFF4C574C),
    outline = Color(0xFFCBD6CB),
    outlineVariant = Color(0xFFDEE6DE),
    error = Color(0xFFD70015),
    onError = Color.White
)

@Composable
fun BitchatTheme(
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit
) {
    // App-level override from ThemePreferenceManager
    val themePref by ThemePreferenceManager.themeFlow.collectAsState(initial = ThemePreference.System)
    val shouldUseDark = when (darkTheme) {
        true -> true
        false -> false
        null -> when (themePref) {
            ThemePreference.Dark -> true
            ThemePreference.Light -> false
            ThemePreference.System -> isSystemInDarkTheme()
        }
    }

    val context = LocalContext.current
    // Material You: track the wallpaper on Android 12+, else fall back to the fixed modern schemes.
    val dynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = when {
        dynamicColor && shouldUseDark -> dynamicDarkColorScheme(context)
        dynamicColor && !shouldUseDark -> dynamicLightColorScheme(context)
        shouldUseDark -> DarkBitchatColorScheme
        else -> LightBitchatColorScheme
    }
    val palette = if (shouldUseDark) DarkBitchatPalette else LightBitchatPalette

    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.setSystemBarsAppearance(
                    if (!shouldUseDark) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                )
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = if (!shouldUseDark) {
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                } else 0
            }
            window.navigationBarColor = colorScheme.background.toArgb()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
        }
    }

    CompositionLocalProvider(LocalBitchatPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = BitchatShapes,
            content = content
        )
    }
}
