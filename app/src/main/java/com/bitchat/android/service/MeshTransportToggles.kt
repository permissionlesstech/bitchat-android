package com.bitchat.android.service

import com.bitchat.android.ui.debug.TransportToggles
import com.bitchat.android.wifiaware.WifiAwareController

/** The phone's transports behind the shared debug toggles. */
object MeshTransportToggles : TransportToggles {
    override fun setBleEnabled(enabled: Boolean) {
        MeshServiceHolder.meshService?.setBleTransportEnabled(enabled)
    }

    override fun setWifiAwareEnabled(enabled: Boolean) {
        WifiAwareController.setEnabled(enabled)
    }
}
