package com.bitchat.android.ui.debug

import android.content.Context
import com.bitchat.android.storage.StorageDefinitions
import com.bitchat.android.storage.StorageModule
import com.bitchat.android.storage.StorageRepository

object DebugPreferenceManager {
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

    private lateinit var storage: StorageRepository

    fun init(context: Context) {
        storage = StorageModule.repository(context, StorageDefinitions.DebugPreferences)
    }

    private fun ready(): Boolean = ::storage.isInitialized

    fun getVerboseLogging(default: Boolean = false): Boolean =
        if (ready()) storage.getBoolean(KEY_VERBOSE, default) else default

    fun setVerboseLogging(value: Boolean) {
        if (ready()) storage.putBoolean(KEY_VERBOSE, value)
    }

    fun getGattServerEnabled(default: Boolean = true): Boolean =
        if (ready()) storage.getBoolean(KEY_GATT_SERVER, default) else default

    fun setGattServerEnabled(value: Boolean) {
        if (ready()) storage.putBoolean(KEY_GATT_SERVER, value)
    }

    fun getGattClientEnabled(default: Boolean = true): Boolean =
        if (ready()) storage.getBoolean(KEY_GATT_CLIENT, default) else default

    fun setGattClientEnabled(value: Boolean) {
        if (ready()) storage.putBoolean(KEY_GATT_CLIENT, value)
    }

    fun getPacketRelayEnabled(default: Boolean = true): Boolean =
        if (ready()) storage.getBoolean(KEY_PACKET_RELAY, default) else default

    fun setPacketRelayEnabled(value: Boolean) {
        if (ready()) storage.putBoolean(KEY_PACKET_RELAY, value)
    }

    fun getMaxConnectionsOverall(default: Int = 8): Int =
        if (ready()) storage.getInt(KEY_MAX_CONN_OVERALL, default) else default

    fun setMaxConnectionsOverall(value: Int) {
        if (ready()) storage.putInt(KEY_MAX_CONN_OVERALL, value)
    }

    fun getMaxConnectionsServer(default: Int = 8): Int =
        if (ready()) storage.getInt(KEY_MAX_CONN_SERVER, default) else default

    fun setMaxConnectionsServer(value: Int) {
        if (ready()) storage.putInt(KEY_MAX_CONN_SERVER, value)
    }

    fun getMaxConnectionsClient(default: Int = 8): Int =
        if (ready()) storage.getInt(KEY_MAX_CONN_CLIENT, default) else default

    fun setMaxConnectionsClient(value: Int) {
        if (ready()) storage.putInt(KEY_MAX_CONN_CLIENT, value)
    }

    fun getSeenPacketCapacity(default: Int = 500): Int =
        if (ready()) storage.getInt(KEY_SEEN_PACKET_CAP, default) else default

    fun setSeenPacketCapacity(value: Int) {
        if (ready()) storage.putInt(KEY_SEEN_PACKET_CAP, value)
    }

    fun getGcsMaxFilterBytes(default: Int = 400): Int =
        if (ready()) storage.getInt(KEY_GCS_MAX_BYTES, default) else default

    fun setGcsMaxFilterBytes(value: Int) {
        if (ready()) storage.putInt(KEY_GCS_MAX_BYTES, value)
    }

    fun getGcsFprPercent(default: Double = 1.0): Double =
        if (ready()) java.lang.Double.longBitsToDouble(storage.getLong(KEY_GCS_FPR, java.lang.Double.doubleToRawLongBits(default))) else default

    fun setGcsFprPercent(value: Double) {
        if (ready()) storage.putLong(KEY_GCS_FPR, java.lang.Double.doubleToRawLongBits(value))
    }
}
