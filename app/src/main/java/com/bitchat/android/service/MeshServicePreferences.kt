package com.bitchat.android.service

import android.content.Context
import com.bitchat.android.storage.StorageDefinitions
import com.bitchat.android.storage.StorageModule
import com.bitchat.android.storage.StorageRepository

object MeshServicePreferences {
    private const val KEY_AUTO_START = "auto_start_on_boot"
    private const val KEY_BACKGROUND_ENABLED = "background_enabled"

    private lateinit var storage: StorageRepository

    fun init(context: Context) {
        storage = StorageModule.repository(context, StorageDefinitions.MeshServicePreferences)
    }

    private fun ready(): Boolean = ::storage.isInitialized

    fun isAutoStartEnabled(default: Boolean = true): Boolean {
        return if (ready()) storage.getBoolean(KEY_AUTO_START, default) else default
    }

    fun setAutoStartEnabled(enabled: Boolean) {
        if (ready()) storage.putBoolean(KEY_AUTO_START, enabled)
    }

    fun isBackgroundEnabled(default: Boolean = true): Boolean {
        return if (ready()) storage.getBoolean(KEY_BACKGROUND_ENABLED, default) else default
    }

    fun setBackgroundEnabled(enabled: Boolean) {
        if (ready()) storage.putBoolean(KEY_BACKGROUND_ENABLED, enabled)
    }
}
