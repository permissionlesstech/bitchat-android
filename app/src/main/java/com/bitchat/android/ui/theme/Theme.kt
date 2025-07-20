package com.bitchat.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.bitchat.android.ui.SettingsManager

// Colors that match the iOS bitchat theme
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF00FF00),        // Bright green (terminal-like)
    onPrimary = Color.Black,
    secondary = Color(0xFF00CC00),      // Darker green
    onSecondary = Color.Black,
    background = Color.Black,
    onBackground = Color(0xFF00FF00),   // Green on black
    surface = Color(0xFF111111),        // Very dark gray
    onSurface = Color(0xFF00FF00),      // Green text
    error = Color(0xFFFF5555),          // Red for errors
    onError = Color.Black
)

private val LightColorScheme = lightColorScheme(
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

@Composable
fun BitchatTheme(
    themePreference: SettingsManager.ThemePreference = SettingsManager.ThemePreference.SYSTEM,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val supportsDynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    
    val darkTheme = when (themePreference) {
        SettingsManager.ThemePreference.LIGHT -> false
        SettingsManager.ThemePreference.DARK -> true
        SettingsManager.ThemePreference.SYSTEM -> isSystemInDarkTheme()
        SettingsManager.ThemePreference.DYNAMIC -> isSystemInDarkTheme()
    }
    
    val colorScheme = when {
        themePreference == SettingsManager.ThemePreference.DYNAMIC && supportsDynamicColor -> {
            if (darkTheme) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context)
            }
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
