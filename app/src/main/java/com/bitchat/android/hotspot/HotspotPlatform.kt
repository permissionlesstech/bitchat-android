package com.bitchat.android.hotspot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.edit

internal interface HotspotPlatform {
    fun missingPermissions(): Set<String>

    fun activate(
        onP2pStateChanged: (Int) -> Unit,
        onConnectionChanged: () -> Unit
    )

    fun deactivate()
}

internal interface OwnedGroupStore {
    var name: String?
}

internal class SharedPreferencesOwnedGroupStore(context: Context) : OwnedGroupStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var name: String?
        get() = prefs.getString(KEY_OWNED_GROUP, null)
        set(value) {
            prefs.edit { putString(KEY_OWNED_GROUP, value) }
        }

    private companion object {
        const val PREFS_NAME = "hotspot"
        const val KEY_OWNED_GROUP = "owned_group_name"
    }
}

internal class AndroidHotspotPlatform(
    private val context: Context
) : HotspotPlatform {
    private var receiver: BroadcastReceiver? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun missingPermissions(): Set<String> =
        HotspotPermissions.missingFrom(context).toSet()

    override fun activate(
        onP2pStateChanged: (Int) -> Unit,
        onConnectionChanged: () -> Unit
    ) {
        if (receiver == null) {
            receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                            onP2pStateChanged(
                                intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                            )
                        }

                        WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION ->
                            onConnectionChanged()
                    }
                }
            }.also {
                context.registerReceiver(
                    it,
                    IntentFilter().apply {
                        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                    }
                )
            }
        }
        acquireLocks()
    }

    override fun deactivate() {
        val registeredReceiver = receiver
        receiver = null
        try {
            registeredReceiver?.let {
                context.unregisterReceiver(it)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Unable to unregister P2P receiver", e)
        } finally {
            releaseLocks()
        }
    }

    private fun acquireLocks() {
        try {
            val powerManager =
                context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "BitChat:HotspotWakeLock"
            ).apply {
                acquire(30 * 60 * 1000L)
            }

            val wifiManager =
                context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lockType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL
            }
            wifiLock = wifiManager.createWifiLock(
                lockType,
                "BitChat:HotspotWifiLock"
            ).apply {
                acquire()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring hotspot locks", e)
            releaseLocks()
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
            wifiLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing hotspot locks", e)
        } finally {
            wakeLock = null
            wifiLock = null
        }
    }

    private companion object {
        const val TAG = "HotspotPlatform"
    }
}
