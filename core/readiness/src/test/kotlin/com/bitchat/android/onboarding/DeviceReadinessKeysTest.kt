package com.bitchat.android.onboarding

import com.bitchat.android.ui.debug.DebugPreferenceManager
import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The upgrade path. Every installed copy has these files and keys on disk, so
 * the readiness code must read and write them under exactly these names. A key
 * read under a new name would send every existing user back through first-launch
 * onboarding. The names are literals here on purpose, not the production
 * constants.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeviceReadinessKeysTest {
    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        application.getSharedPreferences("bitchat_permissions", Context.MODE_PRIVATE)
            .edit().clear().commit()
        application.getSharedPreferences("bitchat_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun readiness() = SystemDeviceReadiness(application, DebugPreferenceManager()) { false }

    @Test
    fun existingUsersStoredChoicesAreReadUnderTheirOldNames() {
        application.getSharedPreferences("bitchat_permissions", Context.MODE_PRIVATE)
            .edit().putBoolean("first_time_onboarding_complete", true).commit()
        application.getSharedPreferences("bitchat_settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("battery_optimization_skipped", true)
            .putBoolean("background_location_skipped", true)
            .commit()

        val snapshot = readiness().snapshot()

        assertFalse(snapshot.firstLaunch)
        assertTrue(snapshot.batterySkipped)
        assertTrue(snapshot.backgroundLocationSkipped)
    }

    @Test
    fun freshInstallIsAFirstLaunchWithNothingSkipped() {
        val snapshot = readiness().snapshot()

        assertTrue(snapshot.firstLaunch)
        assertFalse(snapshot.batterySkipped)
        assertFalse(snapshot.backgroundLocationSkipped)
    }

    @Test
    fun writesLandUnderTheOldNames() {
        val readiness = readiness()

        readiness.markOnboardingComplete()
        readiness.setBatterySkipped()
        readiness.setBackgroundLocationSkipped()
        readiness.markOptionalPermissionsRequested(listOf(Manifest.permission.POST_NOTIFICATIONS))

        val permissions = application.getSharedPreferences("bitchat_permissions", Context.MODE_PRIVATE)
        val settings = application.getSharedPreferences("bitchat_settings", Context.MODE_PRIVATE)
        assertTrue(permissions.getBoolean("first_time_onboarding_complete", false))
        assertTrue(
            permissions.getBoolean(
                "optional_permission_requested_android.permission.POST_NOTIFICATIONS",
                false
            )
        )
        assertTrue(settings.getBoolean("battery_optimization_skipped", false))
        assertTrue(settings.getBoolean("background_location_skipped", false))
    }
}
