package com.bitchat.watch

import android.app.Application
import com.bitchat.android.identity.scope.CurrentIdentity
import com.bitchat.android.mesh.PowerManager
import com.bitchat.watch.notification.WearNotificationCoordinator
import com.bitchat.watch.ui.WearPeerIdentityState
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import javax.inject.Provider

@HiltAndroidApp
class BitchatWatchApplication : Application() {

    // Lazy or Provider: Hilt injects these fields inside super.onCreate(), and each is first built,
    // or initialised, at the line below that builds it today, in that order. The peer identity
    // state is the identity scope's, so it is read here, as it always was.
    @Inject lateinit var powerManager: Lazy<PowerManager>
    @Inject lateinit var notificationCoordinator: Lazy<WearNotificationCoordinator>
    @Inject @CurrentIdentity lateinit var peerIdentity: Provider<WearPeerIdentityState>

    override fun onCreate() {
        super.onCreate()
        powerManager.get().start()
        notificationCoordinator.get()
        peerIdentity.get().initialize(applicationContext)
    }
}
