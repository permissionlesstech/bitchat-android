package com.bitchat.android.hotspot

import android.content.Context
import androidx.core.content.edit

/**
 * Durable evidence for a Wi-Fi Direct group created by this app.
 *
 * A group can outlive this process, so the exact name must survive restart. The store
 * never infers ownership: callers must verify a live group against the recorded name
 * before issuing the device-scoped removeGroup command.
 */
internal interface OwnedGroupStore {
    fun readName(): String?
    fun saveName(name: String)
    fun clear()
}

internal class SharedPreferencesOwnedGroupStore(context: Context) : OwnedGroupStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun readName(): String? = prefs.getString(KEY_OWNED_GROUP, null)

    override fun saveName(name: String) {
        prefs.edit { putString(KEY_OWNED_GROUP, name) }
    }

    override fun clear() {
        prefs.edit { remove(KEY_OWNED_GROUP) }
    }

    private companion object {
        const val PREFS_NAME = "hotspot"
        const val KEY_OWNED_GROUP = "owned_group_name"
    }
}
