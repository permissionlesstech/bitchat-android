package com.bitchat.android.wifiaware

/**
 * Wear shim for the phone's WifiAwareController.
 *
 * Referenced only by the shared `DebugSettingsManager` debug-UI toggle. Wi-Fi Aware is out of
 * scope for the watch (Bluetooth mesh only), so this is a no-op.
 */
object WifiAwareController {
    fun setEnabled(value: Boolean) = Unit
}
