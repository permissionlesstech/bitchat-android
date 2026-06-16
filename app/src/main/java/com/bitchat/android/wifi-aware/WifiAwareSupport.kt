package com.bitchat.android.wifiaware

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.aware.WifiAwareManager
import android.os.Build

/**
 * Centralized Wi-Fi Aware capability checks.
 *
 * "Supported" is stable device/API capability. "Available" is runtime state and can change
 * when Wi-Fi, location, airplane mode, or system radio state changes.
 */
object WifiAwareSupport {
    data class Status(
        val supported: Boolean,
        val available: Boolean,
        val reason: String? = null
    )

    fun evaluate(context: Context): Status {
        val appContext = context.applicationContext

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return Status(
                supported = false,
                available = false,
                reason = "requires Android 10+"
            )
        }

        val hasFeature = try {
            appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
        } catch (_: Exception) {
            false
        }
        if (!hasFeature) {
            return Status(
                supported = false,
                available = false,
                reason = "device does not advertise Wi-Fi Aware support"
            )
        }

        val manager = getManager(appContext)
            ?: return Status(
                supported = false,
                available = false,
                reason = "WifiAwareManager unavailable"
            )

        val available = try {
            manager.isAvailable
        } catch (_: Exception) {
            false
        }

        return Status(
            supported = true,
            available = available,
            reason = if (available) null else "Wi-Fi Aware temporarily unavailable"
        )
    }

    fun isSupported(context: Context): Boolean = evaluate(context).supported

    fun getManager(context: Context): WifiAwareManager? {
        return try {
            context.applicationContext.getSystemService(WifiAwareManager::class.java)
        } catch (_: Exception) {
            null
        }
    }
}
