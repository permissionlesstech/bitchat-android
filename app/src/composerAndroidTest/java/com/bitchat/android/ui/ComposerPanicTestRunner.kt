package com.bitchat.android.ui

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Synthetic offline configuration; preserves the real Application and ViewModel startup. */
class ComposerPanicTestRunner : AndroidJUnitRunner() {
    override fun callApplicationOnCreate(app: Application) {
        check(app.packageName == "com.bitchat.droid.composerTest") {
            "Panic tests require the dedicated composer test installation"
        }
        app.getSharedPreferences("bitchat_permissions", Context.MODE_PRIVATE).edit()
            .putBoolean("first_time_onboarding_complete", true)
            .putBoolean("optional_permission_requested_android.permission.POST_NOTIFICATIONS", true).commit()
        app.getSharedPreferences("bitchat_debug_settings", Context.MODE_PRIVATE).edit()
            .putBoolean("ble_enabled", false).putBoolean("wifi_aware_enabled", false).commit()
        app.getSharedPreferences("bitchat_settings", Context.MODE_PRIVATE).edit()
            .putBoolean("battery_optimization_skipped", true)
            .putBoolean("background_location_skipped", true).putString("tor_mode", "OFF").commit()
        app.getSharedPreferences("bitchat_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("location_services_enabled", false).commit()
        super.callApplicationOnCreate(app)
    }
}
