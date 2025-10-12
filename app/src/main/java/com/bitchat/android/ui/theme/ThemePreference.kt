package com.bitchat.android.ui.theme

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bitchat.android.model.logWarn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json


private const val THEME = "SystemTheme"

/**
 * App theme preference: System default, Light, or Dark.
 */
@Serializable
@SerialName(THEME)
enum class ThemePreference {
    @SerialName("$THEME.default")
    System,

    @SerialName("$THEME.light")
    Light,

    @SerialName("$THEME.dark")
    Dark;

    val isSystem: Boolean get() = this == System
    val isLight: Boolean get() = this == Light
    val isDark: Boolean get() = this == Dark
}

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

interface ThemePreferenceRepo {
    val theme: StateFlow<ThemePreference>
    suspend fun updateTheme(t: ThemePreference)
}

class ThemePrefRepoImpl(private val context: Context, scope: CoroutineScope) : ThemePreferenceRepo {
    companion object {
        private val KEY_THEME = stringPreferencesKey("theme")
        private const val PREFS_NAME = "bitchat_settings"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    override val theme = context.dataStore.data.map { preferences ->
        (preferences[KEY_THEME] ?: prefs.getString("theme_preference", ThemePreference.System.name))
            ?.let {
                try {
                    Json.decodeFromString<ThemePreference>(it)
                } catch (_: Exception) {
                    ThemePreference.System
                }
            } ?: ThemePreference.System
    }.stateIn(
        scope = scope, started = SharingStarted.Eagerly, initialValue = ThemePreference.System
    )

    override suspend fun updateTheme(t: ThemePreference) = withContext(Dispatchers.IO) {
        try {
            context.dataStore.edit { preferences ->
                preferences[KEY_THEME] = Json.encodeToString(ThemePreference.serializer(), t)
            }
        } catch (e: Exception) {
            logWarn("Failed to update theme: ${e.printStackTrace()}")
            return@withContext
        }
    }
}


