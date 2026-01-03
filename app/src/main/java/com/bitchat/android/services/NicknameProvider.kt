package com.bitchat.android.services

import android.content.Context
import com.bitchat.android.ui.DataManager

/**
 * Provides current user's nickname for announcements and leave messages.
 * If no nickname saved, falls back to the provided peerID.
 */
object NicknameProvider {
    fun getNickname(context: Context, myPeerID: String): String {
        return try {
            val dm = DataManager(context.applicationContext)
            val nick = dm.loadNickname()
            if (nick.isNullOrBlank()) myPeerID else nick
        } catch (_: Exception) {
            myPeerID
        }
    }
}

