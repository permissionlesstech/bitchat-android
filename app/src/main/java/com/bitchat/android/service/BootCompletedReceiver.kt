package com.bitchat.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

class BootCompletedReceiver : BroadcastReceiver() {

    /**
     * The receiver is built by the system, so it reaches the preferences through an entry point.
     * (Kotlin cannot call the `super.onReceive` an `@AndroidEntryPoint` receiver needs.)
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun meshServicePreferences(): MeshServicePreferences
    }

    override fun onReceive(context: Context, intent: Intent) {
        val meshServicePreferences = EntryPointAccessors
            .fromApplication(context.applicationContext, Dependencies::class.java)
            .meshServicePreferences()

        // Ensure preferences are initialized on cold boot before reading values
        try { meshServicePreferences.init(context.applicationContext) } catch (_: Exception) { }

        if (meshServicePreferences.isAutoStartEnabled(true)) {
            MeshForegroundService.start(context.applicationContext)
        }
    }
}
