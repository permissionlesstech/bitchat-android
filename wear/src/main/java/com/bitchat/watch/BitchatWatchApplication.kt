package com.bitchat.watch

import android.app.Application
import com.bitchat.android.mesh.PowerManager

class BitchatWatchApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PowerManager.getInstance(applicationContext)
    }
}
