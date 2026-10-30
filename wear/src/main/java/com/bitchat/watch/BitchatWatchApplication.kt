package com.bitchat.watch

import android.app.Application
import com.bitchat.android.mesh.PowerManager
import com.bitchat.watch.notification.WearNotificationCoordinator
import com.bitchat.watch.ui.WearPeerIdentityState
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class BitchatWatchApplication : Application() {

    // Lazy: Hilt injects these fields inside super.onCreate(), and each is first built, or
    // initialised, at the line below that builds it today, in that order.
    @Inject lateinit var powerManager: Lazy<PowerManager>
    @Inject lateinit var notificationCoordinator: Lazy<WearNotificationCoordinator>
    @Inject lateinit var peerIdentity: Lazy<WearPeerIdentityState>

    override fun onCreate() {
        super.onCreate()
        powerManager.get()
        notificationCoordinator.get()
        peerIdentity.get().initialize(applicationContext)
    }
}
