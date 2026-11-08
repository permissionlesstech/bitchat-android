package com.bitchat.android.service

import com.bitchat.android.identity.scope.IdentityScoped
import com.bitchat.android.ui.debug.TransportToggles
import com.bitchat.android.wifiaware.WifiAwareController
import javax.inject.Inject

/** The phone's transports behind the shared debug toggles. */
@IdentityScoped
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
