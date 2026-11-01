package com.bitchat.android.ui

import android.app.Activity
import android.content.BroadcastReceiver

/**
 * Where a tapped notification opens, and what handles its mark-read and
 * reply actions. The app names its own classes; the notifications only
 * address them.
 */
data class NotificationTargets(
    val activity: Class<out Activity>,
    val actionReceiver: Class<out BroadcastReceiver>,
)
