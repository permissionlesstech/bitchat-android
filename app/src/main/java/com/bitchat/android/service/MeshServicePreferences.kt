package com.bitchat.android.service

import android.content.Context
import android.content.SharedPreferences

object MeshServicePreferences {
    private const val PREFS_NAME = "bitchat_mesh_service_prefs"
    private const val KEY_PERSISTENT_NOTIFICATION = "persistent_notification_enabled"
    private const val KEY_AUTO_START = "auto_start_on_boot"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isPersistentNotificationEnabled(default: Boolean = true): Boolean {
        return prefs.getBoolean(KEY_PERSISTENT_NOTIFICATION, default)
    }

    fun setPersistentNotificationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PERSISTENT_NOTIFICATION, enabled).apply()
    }

    fun isAutoStartEnabled(default: Boolean = true): Boolean {
        return prefs.getBoolean(KEY_AUTO_START, default)
    }

    fun setAutoStartEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_START, enabled).apply()
    }
}

