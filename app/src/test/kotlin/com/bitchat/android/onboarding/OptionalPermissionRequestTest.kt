package com.bitchat.android.onboarding

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OptionalPermissionRequestTest {
    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        application.getSharedPreferences("bitchat_permissions", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun `missing notification permission is offered once`() {
        val permissionManager = PermissionManager(application)

        assertEquals(
            listOf(Manifest.permission.POST_NOTIFICATIONS),
            permissionManager.getUnrequestedOptionalPermissions()
        )

        permissionManager.markOptionalPermissionsRequested(
            listOf(Manifest.permission.POST_NOTIFICATIONS)
        )

        assertTrue(permissionManager.getUnrequestedOptionalPermissions().isEmpty())
    }

    @Test
    fun `granted notification permission is not requested`() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertTrue(PermissionManager(application).getUnrequestedOptionalPermissions().isEmpty())
    }
}
