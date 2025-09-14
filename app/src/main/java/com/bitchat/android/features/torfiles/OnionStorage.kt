package com.bitchat.android.features.torfiles

import android.content.Context
import android.util.Log

/**
 * Simple storage for onion address in secure prefs used elsewhere in the app.
 */
object OnionStorage {
    private const val KEY_MY_ONION = "my_onion_address"

    fun saveMyOnionAddress(context: Context, onion: String) {
        try {
            com.bitchat.android.identity.SecureIdentityStateManager(context)
                .storeSecureValue(KEY_MY_ONION, onion)
        } catch (e: Exception) {
            Log.w("OnionStorage", "Failed saving onion: ${e.message}")
        }
    }

    fun loadMyOnionAddress(context: Context): String? {
        return try {
            com.bitchat.android.identity.SecureIdentityStateManager(context)
                .getSecureValue(KEY_MY_ONION)
        } catch (_: Exception) { null }
    }
}

