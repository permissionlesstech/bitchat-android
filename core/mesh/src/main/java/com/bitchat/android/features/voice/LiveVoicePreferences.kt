package com.bitchat.android.features.voice

import android.content.Context

/** One preference gates live PTT sending and playback; finalized voice notes remain available. */
object LiveVoicePreferences {
    private const val PREFERENCES = "bitchat_settings"
    private const val ENABLED = "ptt.liveVoiceEnabled"

    fun isEnabled(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ENABLED, enabled)
            .apply()
    }
}
