package com.bitchat.android.ui

import android.content.Context

object ClientPrivacyPreferences {
    fun showNotificationPreviews(context: Context): Boolean =
        context.getSharedPreferences("client_privacy", Context.MODE_PRIVATE).getBoolean("notification_previews", false)

    fun setNotificationPreviews(context: Context, enabled: Boolean) {
        context.getSharedPreferences("client_privacy", Context.MODE_PRIVATE).edit()
            .putBoolean("notification_previews", enabled).apply()
        NotificationManager.clearAllForPrivacyChange(context)
    }
}
