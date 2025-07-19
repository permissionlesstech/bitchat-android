package com.bitchat.android.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Manages app settings and preferences
 */
class SettingsManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("bitchat_settings", Context.MODE_PRIVATE)
    
    // Theme preference state
    var themePreference by mutableStateOf(loadThemePreference())
        private set
    
    /**
     * Theme options available to users
     */
    enum class ThemePreference {
        SYSTEM,  // Follow system theme
        LIGHT,   // Always light theme
        DARK     // Always dark theme
    }
    
    /**
     * Load the current theme preference from SharedPreferences
     */
    private fun loadThemePreference(): ThemePreference {
        val savedTheme = prefs.getString("theme_preference", "SYSTEM")
        return try {
            ThemePreference.valueOf(savedTheme ?: "SYSTEM")
        } catch (e: IllegalArgumentException) {
            ThemePreference.SYSTEM
        }
    }
    
    /**
     * Update the theme preference
     */
    fun updateThemePreference(preference: ThemePreference) {
        themePreference = preference
        prefs.edit().putString("theme_preference", preference.name).apply()
    }
    
    /**
     * Get display name for theme preference
     */
    fun getThemeDisplayName(preference: ThemePreference): String {
        return when (preference) {
            ThemePreference.SYSTEM -> "System"
            ThemePreference.LIGHT -> "Light"
            ThemePreference.DARK -> "Dark"
        }
    }
}