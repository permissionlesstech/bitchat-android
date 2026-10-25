package com.bitchat.android.spam

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists the user's on/off choice for [SpamFilter.shared]. The filter is on by default.
 */
object SpamFilterPreferences {

    private const val PREFS_NAME = "spam_filter_preferences"
    private const val KEY_ENABLED = "spam_filter_enabled"
    private const val DEFAULT_ENABLED = true

    private val _enabled = MutableStateFlow(DEFAULT_ENABLED)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    @Volatile
    private var sharedPrefs: SharedPreferences? = null

    fun init(context: Context) {
        if (sharedPrefs != null) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sharedPrefs = prefs
        apply(prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED))
    }

    fun setEnabled(enabled: Boolean) {
        if (_enabled.value == enabled) return
        sharedPrefs?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
        apply(enabled)
        // Start from a clean slate so budgets don't carry over from before the toggle.
        SpamFilter.shared.reset()
    }

    private fun apply(enabled: Boolean) {
        _enabled.value = enabled
        SpamFilter.shared.enabled = enabled
    }
}
