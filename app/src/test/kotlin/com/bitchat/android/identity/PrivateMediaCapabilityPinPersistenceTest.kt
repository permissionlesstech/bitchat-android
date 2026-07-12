package com.bitchat.android.identity

import android.content.Context
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class PrivateMediaCapabilityPinPersistenceTest {
    private val fingerprint = "ab".repeat(32)
    private lateinit var manager: SecureIdentityStateManager
    private lateinit var prefs: android.content.SharedPreferences

    @Before
    fun setup() {
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences(
            "private-media-pin-${UUID.randomUUID()}",
            Context.MODE_PRIVATE
        )
        manager = SecureIdentityStateManager(prefs, testOnly = true)
        manager.clearIdentityData()
    }

    @After
    fun tearDown() {
        manager.clearIdentityData()
    }

    @Test
    fun `capability pin persists and panic identity wipe removes it`() {
        manager.markPrivateMediaCapable(fingerprint)

        val reloaded = SecureIdentityStateManager(prefs, testOnly = true)
        assertTrue(reloaded.isPrivateMediaCapable(fingerprint))

        reloaded.clearIdentityData()
        // Simulate an old BLE/Wi-Fi controller finishing a pre-panic callback
        // after another manager performed the wipe.
        manager.markPrivateMediaCapable(fingerprint)
        val afterPanic = SecureIdentityStateManager(prefs, testOnly = true)
        assertFalse(afterPanic.isPrivateMediaCapable(fingerprint))
        assertTrue(afterPanic.getPrivateMediaCapabilityPinsForTesting().isEmpty())
    }
}
