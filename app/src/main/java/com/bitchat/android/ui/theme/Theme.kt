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

// ============================================================================
// MATRIX skin — the original terminal-inspired identity (iOS parity)
// ============================================================================
private val MatrixDarkColorScheme = darkColorScheme(
    primary = Color(0xFF39FF14),        // Bright green (terminal-like)
    onPrimary = Color.Black,
    secondary = Color(0xFF2ECB10),      // Darker green
    onSecondary = Color.Black,
    background = Color.Black,
    onBackground = Color(0xFF39FF14),   // Green on black
    surface = Color(0xFF111111),        // Very dark gray
    onSurface = Color(0xFF39FF14),      // Green text
    error = Color(0xFFFF5555),          // Red for errors
    onError = Color.Black
)

private val MatrixLightColorScheme = lightColorScheme(
    primary = Color(0xFF008000),        // Dark green
    onPrimary = Color.White,
    secondary = Color(0xFF006600),      // Even darker green
    onSecondary = Color.White,
    background = Color.White,
    onBackground = Color(0xFF008000),   // Dark green on white
    surface = Color(0xFFF8F8F8),        // Very light gray
    onSurface = Color(0xFF008000),      // Dark green text
    error = Color(0xFFCC0000),          // Dark red for errors
    onError = Color.White
)

/**
 * Root theme for bitchat. Resolves two independent axes:
 *
 *  1. [AppSkin] (via [AppSkinPreferenceManager]) — the entire design language
 *     (Matrix terminal vs. Material 3 Expressive).
 *  2. light/dark (via [ThemePreferenceManager], or [darkTheme] override) — applied within a skin.
 *
 * For the Expressive skin we prefer wallpaper-based dynamic color (Material You) on Android 12+,
 * falling back to the bitchat brand palette below that.
 *
 * The resolved [AppSkin] and [ThemeAccents] are published via composition locals so any
 * composable can branch its layout and pull semantic accent colors centrally.
 */
@Composable
fun BitchatTheme(
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    val skin by AppSkinPreferenceManager.skinFlow.collectAsState(initial = AppSkin.MATRIX)
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

    val dynamicColorAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val colorScheme = when (skin) {
        AppSkin.EXPRESSIVE -> when {
            dynamicColorAvailable && shouldUseDark -> dynamicDarkColorScheme(context)
            dynamicColorAvailable -> dynamicLightColorScheme(context)
            shouldUseDark -> ExpressiveDarkColorScheme
            else -> ExpressiveLightColorScheme
        }
        AppSkin.MATRIX -> if (shouldUseDark) MatrixDarkColorScheme else MatrixLightColorScheme
    }

    val typography = if (skin.isExpressive) ExpressiveTypography else Typography
    val shapes = if (skin.isExpressive) ExpressiveShapes else MatrixShapes
    val accents = if (skin.isExpressive) expressiveAccents(colorScheme, shouldUseDark) else MatrixAccents

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

    CompositionLocalProvider(
        LocalAppSkin provides skin,
        LocalThemeAccents provides accents
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = shapes,
            content = content
        )
    }
}
