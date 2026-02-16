package com.bitchat.android.services

import android.content.Context
import com.bitchat.android.ui.DataManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Provides current user's nickname for announcements and leave messages.
 * If no nickname saved, falls back to the provided peerID.
 */
object NicknameProvider {
    suspend fun getNickname(context: Context, myPeerID: String): String {
        return try {
            val dm = DataManager(context.applicationContext)
            withContext(Dispatchers.IO) { dm.initialize() }
            val nick = dm.loadNickname()
            if (nick.isNullOrBlank()) myPeerID else nick
        } catch (_: Exception) {
            myPeerID
        }
    }
}

