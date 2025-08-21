package com.bitchat.android.services

/**
 * Process-wide visibility + focus state used by background service
 * to avoid duplicate notifications when the app is foregrounded or
 * the user is already viewing a specific private chat.
 */
object AppVisibilityState {
    @Volatile
    var isAppInBackground: Boolean = true

    @Volatile
    var currentPrivateChatPeer: String? = null
}

