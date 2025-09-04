package com.bitchat.android.ui.debug

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences-backed persistence for debug settings.
 * Keeps the DebugSettingsManager stateless with regard to Android Context.
 */
object DebugPreferenceManager {
    private const val PREFS_NAME = "bitchat_debug_settings"
    private const val KEY_VERBOSE = "verbose_logging"
    private const val KEY_GATT_SERVER = "gatt_server_enabled"
    private const val KEY_GATT_CLIENT = "gatt_client_enabled"
    private const val KEY_PACKET_RELAY = "packet_relay_enabled"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun ready(): Boolean = ::prefs.isInitialized

    fun getVerboseLogging(default: Boolean = false): Boolean =
        if (ready()) prefs.getBoolean(KEY_VERBOSE, default) else default

    fun setVerboseLogging(value: Boolean) {
        if (ready()) prefs.edit().putBoolean(KEY_VERBOSE, value).apply()
    }

    fun getGattServerEnabled(default: Boolean = true): Boolean =
        if (ready()) prefs.getBoolean(KEY_GATT_SERVER, default) else default

    fun setGattServerEnabled(value: Boolean) {
        if (ready()) prefs.edit().putBoolean(KEY_GATT_SERVER, value).apply()
    }

    fun getGattClientEnabled(default: Boolean = true): Boolean =
        if (ready()) prefs.getBoolean(KEY_GATT_CLIENT, default) else default

    fun setGattClientEnabled(value: Boolean) {
        if (ready()) prefs.edit().putBoolean(KEY_GATT_CLIENT, value).apply()
    }

    fun getPacketRelayEnabled(default: Boolean = true): Boolean =
        if (ready()) prefs.getBoolean(KEY_PACKET_RELAY, default) else default

    fun setPacketRelayEnabled(value: Boolean) {
        if (ready()) prefs.edit().putBoolean(KEY_PACKET_RELAY, value).apply()
    }
}
