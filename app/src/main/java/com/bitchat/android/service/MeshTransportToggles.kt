package com.bitchat.android.service

import com.bitchat.android.ui.debug.TransportToggles
import com.bitchat.android.wifiaware.WifiAwareController
import javax.inject.Inject
import javax.inject.Singleton

/** The phone's transports behind the shared debug toggles. */
@Singleton
class MeshTransportToggles @Inject constructor(
    private val meshServiceHolder: MeshServiceHolder,
    private val wifiAwareController: WifiAwareController,
) : TransportToggles {
    override fun setBleEnabled(enabled: Boolean) {
        meshServiceHolder.meshService?.setBleTransportEnabled(enabled)
    }

    override fun setWifiAwareEnabled(enabled: Boolean) {
        wifiAwareController.setEnabled(enabled)
    }
}
