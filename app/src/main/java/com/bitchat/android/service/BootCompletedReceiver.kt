package com.bitchat.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Ensure preferences are initialized on cold boot before reading values
        try { MeshServicePreferences.init(context.applicationContext) } catch (_: Exception) { }

        if (MeshServicePreferences.isAutoStartEnabled(true)) {
            MeshForegroundService.start(context.applicationContext)
        }
    }
}
