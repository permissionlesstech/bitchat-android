package com.bitchat.android.ui.theme

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Top-level visual "skin" of the app. Unlike [ThemePreference] (which only controls
 * light/dark within a skin), this selects the entire design language.
 *
 * - [MATRIX]: the original terminal-inspired identity — monospace type, black canvas,
 *   phosphor-green accents. bitchat's heritage look.
 * - [EXPRESSIVE]: a bold Material 3 Expressive redesign — vibrant tonal color
 *   (dynamic / Material You where available), large rounded shapes, springy motion,
 *   chat bubbles and emphasized components.
 */
enum class AppSkin {
    MATRIX,
    EXPRESSIVE;

    val isMatrix: Boolean get() = this == MATRIX
    val isExpressive: Boolean get() = this == EXPRESSIVE
}

/**
 * SharedPreferences-backed manager for the active [AppSkin], mirroring the pattern used by
 * [ThemePreferenceManager]. Exposes a [StateFlow] so the whole UI re-composes on change.
 *
 * Default is [AppSkin.MATRIX] to preserve bitchat's established identity on fresh installs.
 */
object AppSkinPreferenceManager {
    private const val PREFS_NAME = "bitchat_settings"
    private const val KEY_SKIN = "app_skin"

    private val _skinFlow = MutableStateFlow(AppSkin.MATRIX)
    val skinFlow: StateFlow<AppSkin> = _skinFlow

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_SKIN, AppSkin.MATRIX.name)
        _skinFlow.value = runCatching { AppSkin.valueOf(saved!!) }.getOrDefault(AppSkin.MATRIX)
    }

    fun set(context: Context, skin: AppSkin) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SKIN, skin.name).apply()
        _skinFlow.value = skin
    }
}

/**
 * The active skin, provided at the root of the composition by [BitchatTheme].
 * Any composable can branch on this to deliver a skin-specific layout.
 */
val LocalAppSkin = staticCompositionLocalOf { AppSkin.MATRIX }

/** Convenience: is the Material 3 Expressive skin currently active? */
@Composable
@ReadOnlyComposable
fun isExpressiveSkin(): Boolean = LocalAppSkin.current.isExpressive
