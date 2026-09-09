package com.bitchat.android.net

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object TorPreferenceManager {
    private const val PREFS_NAME = "bitchat_settings"
    private const val KEY_TOR_MODE = "tor_mode"

    private val _modeFlow = MutableStateFlow(TorMode.ON)
    val modeFlow: StateFlow<TorMode> = _modeFlow

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_TOR_MODE, TorMode.ON.name)
        val mode = runCatching { TorMode.valueOf(saved ?: TorMode.ON.name) }.getOrDefault(TorMode.ON)
        _modeFlow.value = mode
    }

    fun set(context: Context, mode: TorMode) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_TOR_MODE, mode.name).apply()
        _modeFlow.value = mode
    }

    fun get(context: Context): TorMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_TOR_MODE, TorMode.ON.name)
        return runCatching { TorMode.valueOf(saved ?: TorMode.ON.name) }.getOrDefault(TorMode.ON)
    }
}

