package com.bitchat.android.ui.theme

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsetsController
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView

// Colors that match the iOS bitchat theme.
// Surface/outline/error slots are derived from BitchatPalette so that screens which have not
// yet migrated to the explicit palette still pick up the redesigned tinted surfaces.
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF39FF14),        // Bright green (terminal-like)
    onPrimary = Color.Black,
    secondary = Color(0xFF2ECB10),      // Darker green
    onSecondary = Color.Black,
    background = DarkBitchatPalette.background,
    onBackground = Color(0xFF39FF14),   // Green on near-black
    surface = DarkBitchatPalette.surface,
    onSurface = Color(0xFF39FF14),      // Green text
    surfaceVariant = DarkBitchatPalette.surfaceVariant,
    onSurfaceVariant = DarkBitchatPalette.textSecondary,
    outline = DarkBitchatPalette.outline,
    outlineVariant = DarkBitchatPalette.outlineVariant,
    error = DarkBitchatPalette.accentRed,
    onError = Color.Black
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF008000),        // Dark green
    onPrimary = Color.White,
    secondary = Color(0xFF006600),      // Even darker green
    onSecondary = Color.White,
    background = LightBitchatPalette.background,
    onBackground = Color(0xFF008000),   // Dark green on off-white
    surface = LightBitchatPalette.surface,
    onSurface = Color(0xFF008000),      // Dark green text
    surfaceVariant = LightBitchatPalette.surfaceVariant,
    onSurfaceVariant = LightBitchatPalette.textSecondary,
    outline = LightBitchatPalette.outline,
    outlineVariant = LightBitchatPalette.outlineVariant,
    error = LightBitchatPalette.accentRed,
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

    val colorScheme = if (shouldUseDark) DarkColorScheme else LightColorScheme
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
            content = content
        )
    }
}
