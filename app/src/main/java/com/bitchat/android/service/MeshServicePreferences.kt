package com.bitchat.android.service

import android.content.Context
import android.content.SharedPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the mesh starts on boot and runs in the background. The constructor does no I/O;
 * [init] opens the preferences, from `Application.onCreate` and from the boot receiver.
 */
@Singleton
class MeshServicePreferences @Inject constructor() {
    private companion object {
        const val PREFS_NAME = "bitchat_mesh_service_prefs"
        const val KEY_AUTO_START = "auto_start_on_boot"
        const val KEY_BACKGROUND_ENABLED = "background_enabled"
    }

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isAutoStartEnabled(default: Boolean = true): Boolean {
        return prefs.getBoolean(KEY_AUTO_START, default)
    }

    fun setAutoStartEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_START, enabled).apply()
    }

    fun isBackgroundEnabled(default: Boolean = true): Boolean {
        return prefs.getBoolean(KEY_BACKGROUND_ENABLED, default)
    }

    fun setBackgroundEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BACKGROUND_ENABLED, enabled).apply()
    }
}
