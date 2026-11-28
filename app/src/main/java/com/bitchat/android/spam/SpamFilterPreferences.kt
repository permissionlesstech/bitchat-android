package com.bitchat.android.spam

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists the user's on/off choice for [SpamFilter.shared] (on by default), plus the debug
 * settings: tuned limits and the bypass that marks spam instead of hiding it.
 */
object SpamFilterPreferences {

    private const val PREFS_NAME = "spam_filter_preferences"
    private const val KEY_ENABLED = "spam_filter_enabled"
    private const val KEY_DEBUG_BYPASS = "spam_filter_debug_bypass"
    private const val KEY_MESSAGE_BURST = "spam_filter_message_burst"
    private const val KEY_SECONDS_PER_MESSAGE = "spam_filter_seconds_per_message"
    private const val KEY_CHARACTERS_PER_MINUTE = "spam_filter_characters_per_minute"
    private const val KEY_LINKS_PER_MINUTE = "spam_filter_links_per_minute"
    private const val KEY_ALLOWED_REPEATS = "spam_filter_allowed_repeats"
    private const val KEY_CROSS_USER_ALLOWED_REPEATS = "spam_filter_cross_user_allowed_repeats"
    private const val KEY_CROSS_USER_MIN_LENGTH = "spam_filter_cross_user_min_length"
    private const val KEY_REPEAT_SIMILARITY = "spam_filter_repeat_similarity"
    private const val KEY_REPEAT_WINDOW_MINUTES = "spam_filter_repeat_window_minutes"
    private const val KEY_SELF_MATCH_THRESHOLD = "spam_filter_self_match_threshold"
    private const val KEY_SELF_MATCH_MIN_LENGTH = "spam_filter_self_match_min_length"
    private const val DEFAULT_ENABLED = true

    private val _enabled = MutableStateFlow(DEFAULT_ENABLED)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _debugBypass = MutableStateFlow(false)
    val debugBypass: StateFlow<Boolean> = _debugBypass.asStateFlow()

    private val _tuning = MutableStateFlow(SpamTuning.DEFAULT)
    val tuning: StateFlow<SpamTuning> = _tuning.asStateFlow()

    @Volatile
    private var sharedPrefs: SharedPreferences? = null

    fun init(context: Context) {
        if (sharedPrefs != null) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sharedPrefs = prefs
        applyEnabled(prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED))
        applyDebugBypass(prefs.getBoolean(KEY_DEBUG_BYPASS, false))
        applyTuning(readTuning(prefs))
    }

    fun setEnabled(enabled: Boolean) {
        if (_enabled.value == enabled) return
        sharedPrefs?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
        applyEnabled(enabled)
        // Start from a clean slate so budgets don't carry over from before the toggle.
        SpamFilter.shared.reset()
    }

    fun setDebugBypass(enabled: Boolean) {
        sharedPrefs?.edit()?.putBoolean(KEY_DEBUG_BYPASS, enabled)?.apply()
        applyDebugBypass(enabled)
    }

    fun setTuning(tuning: SpamTuning) {
        if (_tuning.value == tuning) return
        sharedPrefs?.edit()?.apply {
            putInt(KEY_MESSAGE_BURST, tuning.messageBurst)
            putInt(KEY_SECONDS_PER_MESSAGE, tuning.secondsPerMessage)
            putInt(KEY_CHARACTERS_PER_MINUTE, tuning.charactersPerMinute)
            putInt(KEY_LINKS_PER_MINUTE, tuning.linksPerMinute)
            putInt(KEY_ALLOWED_REPEATS, tuning.allowedRepeats)
            putInt(KEY_CROSS_USER_ALLOWED_REPEATS, tuning.crossUserAllowedRepeats)
            putInt(KEY_CROSS_USER_MIN_LENGTH, tuning.crossUserMinLength)
            putInt(KEY_REPEAT_SIMILARITY, tuning.repeatSimilarityPercent)
            putInt(KEY_REPEAT_WINDOW_MINUTES, tuning.repeatWindowMinutes)
            putInt(KEY_SELF_MATCH_THRESHOLD, tuning.selfMatchPercent)
            putInt(KEY_SELF_MATCH_MIN_LENGTH, tuning.selfMatchMinLength)
            apply()
        }
        applyTuning(tuning)
    }

    private fun readTuning(prefs: SharedPreferences): SpamTuning {
        val d = SpamTuning.DEFAULT
        return SpamTuning(
            messageBurst = prefs.getInt(KEY_MESSAGE_BURST, d.messageBurst),
            secondsPerMessage = prefs.getInt(KEY_SECONDS_PER_MESSAGE, d.secondsPerMessage),
            charactersPerMinute = prefs.getInt(KEY_CHARACTERS_PER_MINUTE, d.charactersPerMinute),
            linksPerMinute = prefs.getInt(KEY_LINKS_PER_MINUTE, d.linksPerMinute),
            allowedRepeats = prefs.getInt(KEY_ALLOWED_REPEATS, d.allowedRepeats),
            crossUserAllowedRepeats = prefs.getInt(KEY_CROSS_USER_ALLOWED_REPEATS, d.crossUserAllowedRepeats),
            crossUserMinLength = prefs.getInt(KEY_CROSS_USER_MIN_LENGTH, d.crossUserMinLength),
            repeatSimilarityPercent = prefs.getInt(KEY_REPEAT_SIMILARITY, d.repeatSimilarityPercent),
            repeatWindowMinutes = prefs.getInt(KEY_REPEAT_WINDOW_MINUTES, d.repeatWindowMinutes),
            selfMatchPercent = prefs.getInt(KEY_SELF_MATCH_THRESHOLD, d.selfMatchPercent),
            selfMatchMinLength = prefs.getInt(KEY_SELF_MATCH_MIN_LENGTH, d.selfMatchMinLength)
        )
    }

    private fun applyEnabled(enabled: Boolean) {
        _enabled.value = enabled
        SpamFilter.shared.enabled = enabled
    }

    private fun applyDebugBypass(enabled: Boolean) {
        _debugBypass.value = enabled
        SpamFilter.shared.debugBypass = enabled
    }

    private fun applyTuning(tuning: SpamTuning) {
        _tuning.value = tuning
        SpamFilter.shared.config = tuning.toConfig()
    }
}
