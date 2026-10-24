package com.bitchat.android.onboarding

import android.content.Context

/**
 * Preference manager for battery optimization skip choice
 */
object BatteryOptimizationPreferenceManager {
    private const val PREFS_NAME = "bitchat_settings"
    private const val KEY_BATTERY_SKIP = "battery_optimization_skipped"

    fun setSkipped(context: Context, skipped: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_BATTERY_SKIP, skipped).apply()
    }

    fun isSkipped(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_BATTERY_SKIP, false)
    }
}
