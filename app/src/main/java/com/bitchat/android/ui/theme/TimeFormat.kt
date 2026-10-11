package com.bitchat.android.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Clock style for transcript timestamps.
 *
 * [System] follows the device's own 12/24-hour setting, which is the right default - a phone set
 * to 12-hour showing `13:42` in one app and `1:42 PM` everywhere else reads as a bug. The explicit
 * options exist because some people want a 24-hour transcript on a 12-hour phone regardless.
 */
enum class TimeFormatPreference {
    System,
    TwelveHour,
    TwentyFourHour
}

/**
 * SharedPreferences-backed clock settings with StateFlows. Mirrors [ThemePreferenceManager].
 *
 * Both values live in one object because they are one user-facing concern - how a timestamp is
 * written - and a second singleton would only duplicate the init/set plumbing.
 */
object TimeFormatPreferenceManager {
    private const val PREFS_NAME = "bitchat_settings"
    private const val KEY_TIME_FORMAT = "time_format"
    private const val KEY_SHOW_SECONDS = "time_show_seconds"

    private val _formatFlow = MutableStateFlow(TimeFormatPreference.System)
    val formatFlow: StateFlow<TimeFormatPreference> = _formatFlow

    private val _showSecondsFlow = MutableStateFlow(false)
    val showSecondsFlow: StateFlow<Boolean> = _showSecondsFlow

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_TIME_FORMAT, TimeFormatPreference.System.name)
        _formatFlow.value = runCatching { TimeFormatPreference.valueOf(saved!!) }
            .getOrDefault(TimeFormatPreference.System)
        _showSecondsFlow.value = prefs.getBoolean(KEY_SHOW_SECONDS, false)
    }

    fun set(context: Context, preference: TimeFormatPreference) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_TIME_FORMAT, preference.name).apply()
        _formatFlow.value = preference
    }

    fun setShowSeconds(context: Context, showSeconds: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_SHOW_SECONDS, showSeconds).apply()
        _showSecondsFlow.value = showSeconds
    }
}
