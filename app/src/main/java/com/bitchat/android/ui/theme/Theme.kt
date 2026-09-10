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

// Standard UI semantics live in Material so stock components and custom Bitchat composables
// share one source of truth. LocalBitchatPalette below only supplies app-specific extra colors.
//
// The neutrals are true greys. Green survives only as the accent - primary, and the peer hues -
// because a green cast on every surface is what made the app read as a terminal rather than a
// chat client. The surfaceContainer* roles are spelled out because M3's defaults for them are
// derived from its purple baseline, which shows up in dialogs, snackbars and the nav bar.
internal val DarkBitchatColorScheme = darkColorScheme(
    primary = Color(0xFF32D74B),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF163D1D),
    onPrimaryContainer = Color(0xFFB8F5C1),
    secondary = Color(0xFF0A84FF),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF082E54),
    onSecondaryContainer = Color(0xFFC2E0FF),
    tertiary = DarkBitchatPalette.accentOrange,
    onTertiary = Color.Black,
    background = Color(0xFF0B0B0D),
    onBackground = Color(0xFFF3F3F5),
    surface = Color(0xFF16161A),
    onSurface = Color(0xFFF3F3F5),
    surfaceVariant = Color(0xFF1F1F24),
    onSurfaceVariant = Color(0xFF9C9CA6),
    surfaceContainerLowest = Color(0xFF08080A),
    surfaceContainerLow = Color(0xFF121215),
    surfaceContainer = Color(0xFF16161A),
    surfaceContainerHigh = Color(0xFF1F1F24),
    surfaceContainerHighest = Color(0xFF29292F),
    outline = Color(0xFF2E2E34),
    outlineVariant = Color(0xFF212126),
    error = Color(0xFFFF453A),
    onError = Color.Black
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
    onBackground = Color(0xFF15151A),
    surface = Color(0xFFF4F4F6),
    onSurface = Color(0xFF15151A),
    surfaceVariant = Color(0xFFEAEAEE),
    onSurfaceVariant = Color(0xFF55555F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8F8FA),
    surfaceContainer = Color(0xFFF4F4F6),
    surfaceContainerHigh = Color(0xFFEEEEF2),
    surfaceContainerHighest = Color(0xFFE7E7EC),
    outline = Color(0xFFD6D6DC),
    outlineVariant = Color(0xFFE6E6EB),
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

    val colorScheme = if (shouldUseDark) DarkBitchatColorScheme else LightBitchatColorScheme
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
