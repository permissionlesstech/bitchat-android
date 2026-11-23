package com.bitchat.android.services

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stores user-facing privacy preferences such as optional screenshot blocking
 * (`FLAG_SECURE`) in private chats.
 */
object PrivacyPreferenceManager {
    private const val PREFS_NAME = "bitchat_privacy_prefs"
    private const val KEY_SCREENSHOT_BLOCK_PRIVATE_CHATS = "screenshot_block_private_chats"

    private val _screenshotBlockPrivateChats = MutableStateFlow(false)
    val screenshotBlockPrivateChats: StateFlow<Boolean> = _screenshotBlockPrivateChats.asStateFlow()

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp
        _screenshotBlockPrivateChats.value = sp.getBoolean(KEY_SCREENSHOT_BLOCK_PRIVATE_CHATS, false)
    }

    fun setScreenshotBlockPrivateChats(enabled: Boolean) {
        _screenshotBlockPrivateChats.value = enabled
        prefs?.edit()?.putBoolean(KEY_SCREENSHOT_BLOCK_PRIVATE_CHATS, enabled)?.apply()
    }

    /**
     * Returns true when `FLAG_SECURE` should be applied to the activity window.
     */
    fun shouldApplyFlagSecure(screenshotBlockEnabled: Boolean, activePrivateChatPeer: String?): Boolean {
        return screenshotBlockEnabled && !activePrivateChatPeer.isNullOrBlank()
    }
}
