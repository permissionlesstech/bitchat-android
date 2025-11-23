package com.bitchat.android.ui.debug

import android.content.Context
import android.content.SharedPreferences
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * SharedPreferences-backed persistence for debug settings.
 * Keeps the DebugSettingsManager stateless with regard to Android Context.
 */
@Singleton
class DebugPreferenceManager @Inject constructor(context: Context) {
    private companion object {
        const val PREFS_NAME = "bitchat_debug_settings"
        const val KEY_VERBOSE = "verbose_logging"
        const val KEY_GATT_SERVER = "gatt_server_enabled"
        const val KEY_GATT_CLIENT = "gatt_client_enabled"
        const val KEY_PACKET_RELAY = "packet_relay_enabled"
        const val KEY_MAX_CONN_OVERALL = "max_connections_overall"
        const val KEY_MAX_CONN_SERVER = "max_connections_server"
        const val KEY_MAX_CONN_CLIENT = "max_connections_client"
        const val KEY_SEEN_PACKET_CAP = "seen_packet_capacity"
        // GCS keys (no migration/back-compat)
        const val KEY_GCS_MAX_BYTES = "gcs_max_filter_bytes"
        const val KEY_GCS_FPR = "gcs_filter_fpr_percent"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getVerboseLogging(default: Boolean = false): Boolean =
        prefs.getBoolean(KEY_VERBOSE, default)

    fun setVerboseLogging(value: Boolean) {
        prefs.edit().putBoolean(KEY_VERBOSE, value).apply()
    }

    fun getGattServerEnabled(default: Boolean = true): Boolean =
        prefs.getBoolean(KEY_GATT_SERVER, default)

    fun setGattServerEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_GATT_SERVER, value).apply()
    }

    fun getGattClientEnabled(default: Boolean = true): Boolean =
        prefs.getBoolean(KEY_GATT_CLIENT, default)

    fun setGattClientEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_GATT_CLIENT, value).apply()
    }

    fun getPacketRelayEnabled(default: Boolean = true): Boolean =
        prefs.getBoolean(KEY_PACKET_RELAY, default)

    fun setPacketRelayEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_PACKET_RELAY, value).apply()
    }

    // Optional connection limits (0 or missing => use defaults)
    fun getMaxConnectionsOverall(default: Int = 8): Int =
        prefs.getInt(KEY_MAX_CONN_OVERALL, default)

    fun setMaxConnectionsOverall(value: Int) {
        prefs.edit().putInt(KEY_MAX_CONN_OVERALL, value).apply()
    }

    fun getMaxConnectionsServer(default: Int = 8): Int =
        prefs.getInt(KEY_MAX_CONN_SERVER, default)

    fun setMaxConnectionsServer(value: Int) {
        prefs.edit().putInt(KEY_MAX_CONN_SERVER, value).apply()
    }

    fun getMaxConnectionsClient(default: Int = 8): Int =
        prefs.getInt(KEY_MAX_CONN_CLIENT, default)

    fun setMaxConnectionsClient(value: Int) {
        prefs.edit().putInt(KEY_MAX_CONN_CLIENT, value).apply()
    }

    // Sync/GCS settings
    fun getSeenPacketCapacity(default: Int = 500): Int =
        prefs.getInt(KEY_SEEN_PACKET_CAP, default)

    fun setSeenPacketCapacity(value: Int) {
        prefs.edit().putInt(KEY_SEEN_PACKET_CAP, value).apply()
    }

    fun getGcsMaxFilterBytes(default: Int = 400): Int =
        prefs.getInt(KEY_GCS_MAX_BYTES, default)

    fun setGcsMaxFilterBytes(value: Int) {
        prefs.edit().putInt(KEY_GCS_MAX_BYTES, value).apply()
    }

    fun getGcsFprPercent(default: Double = 1.0): Double =
        java.lang.Double.longBitsToDouble(prefs.getLong(KEY_GCS_FPR, java.lang.Double.doubleToRawLongBits(default)))

    fun setGcsFprPercent(value: Double) {
        prefs.edit().putLong(KEY_GCS_FPR, java.lang.Double.doubleToRawLongBits(value)).apply()
    }
}
