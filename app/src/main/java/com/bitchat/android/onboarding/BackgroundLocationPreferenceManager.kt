package com.bitchat.android.onboarding

import android.content.Context
import com.bitchat.android.storage.StorageDefinitions
import com.bitchat.android.storage.StorageModule

object BackgroundLocationPreferenceManager {
    private const val KEY_BACKGROUND_LOCATION_SKIP = "background_location_skipped"

    fun setSkipped(context: Context, skipped: Boolean) {
        val storage = StorageModule.repository(context, StorageDefinitions.AppSettings)
        storage.putBoolean(KEY_BACKGROUND_LOCATION_SKIP, skipped)
    }

    fun isSkipped(context: Context): Boolean {
        val storage = StorageModule.repository(context, StorageDefinitions.AppSettings)
        return storage.getBoolean(KEY_BACKGROUND_LOCATION_SKIP, false)
    }
}
