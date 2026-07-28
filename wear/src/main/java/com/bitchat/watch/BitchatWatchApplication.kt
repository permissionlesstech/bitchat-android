package com.bitchat.watch

import android.app.Application
import com.bitchat.android.mesh.PowerManager
import com.bitchat.watch.notification.WearNotificationCoordinator

class BitchatWatchApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PowerManager.getInstance(applicationContext)
        WearNotificationCoordinator.getInstance(applicationContext)
    }
}
