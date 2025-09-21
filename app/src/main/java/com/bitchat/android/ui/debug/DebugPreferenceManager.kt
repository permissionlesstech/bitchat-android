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
    private const val KEY_MAX_CONN_OVERALL = "max_connections_overall"
    private const val KEY_MAX_CONN_SERVER = "max_connections_server"
    private const val KEY_MAX_CONN_CLIENT = "max_connections_client"
    private const val KEY_SEEN_PACKET_CAP = "seen_packet_capacity"
    // GCS keys (no migration/back-compat)
    private const val KEY_GCS_MAX_BYTES = "gcs_max_filter_bytes"
    private const val KEY_GCS_FPR = "gcs_filter_fpr_percent"

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

    // Optional connection limits (0 or missing => use defaults)
    fun getMaxConnectionsOverall(default: Int = 8): Int =
        if (ready()) prefs.getInt(KEY_MAX_CONN_OVERALL, default) else default

    fun setMaxConnectionsOverall(value: Int) {
        if (ready()) prefs.edit().putInt(KEY_MAX_CONN_OVERALL, value).apply()
    }

    fun getMaxConnectionsServer(default: Int = 8): Int =
        if (ready()) prefs.getInt(KEY_MAX_CONN_SERVER, default) else default

    fun setMaxConnectionsServer(value: Int) {
        if (ready()) prefs.edit().putInt(KEY_MAX_CONN_SERVER, value).apply()
    }

    fun getMaxConnectionsClient(default: Int = 8): Int =
        if (ready()) prefs.getInt(KEY_MAX_CONN_CLIENT, default) else default

    fun setMaxConnectionsClient(value: Int) {
        if (ready()) prefs.edit().putInt(KEY_MAX_CONN_CLIENT, value).apply()
    }

    // Sync/GCS settings
    fun getSeenPacketCapacity(default: Int = 500): Int =
        if (ready()) prefs.getInt(KEY_SEEN_PACKET_CAP, default) else default

    fun setSeenPacketCapacity(value: Int) {
        if (ready()) prefs.edit().putInt(KEY_SEEN_PACKET_CAP, value).apply()
    }

    fun getGcsMaxFilterBytes(default: Int = 400): Int =
        if (ready()) prefs.getInt(KEY_GCS_MAX_BYTES, default) else default

    fun setGcsMaxFilterBytes(value: Int) {
        if (ready()) prefs.edit().putInt(KEY_GCS_MAX_BYTES, value).apply()
    }

    fun getGcsFprPercent(default: Double = 1.0): Double =
        if (ready()) java.lang.Double.longBitsToDouble(prefs.getLong(KEY_GCS_FPR, java.lang.Double.doubleToRawLongBits(default))) else default

    fun setGcsFprPercent(value: Double) {
        if (ready()) prefs.edit().putLong(KEY_GCS_FPR, java.lang.Double.doubleToRawLongBits(value)).apply()
    }
}
