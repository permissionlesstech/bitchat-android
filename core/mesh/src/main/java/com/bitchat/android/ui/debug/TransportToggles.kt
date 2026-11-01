package com.bitchat.android.ui.debug

/**
 * Applies the debug transport master toggles to the running transports.
 *
 * [DebugSettingsManager] is shared with the watch, which has neither the phone's mesh service
 * nor Wi-Fi Aware, so it cannot reach them itself. The phone registers an implementation at
 * start-up through [DebugSettingsManager.transportToggles]; the watch registers none.
 */
interface TransportToggles {
    fun setBleEnabled(enabled: Boolean)
    fun setWifiAwareEnabled(enabled: Boolean)
}
