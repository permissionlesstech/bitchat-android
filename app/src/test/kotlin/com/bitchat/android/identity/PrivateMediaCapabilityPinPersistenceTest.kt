package com.bitchat.android.identity

import android.content.Context
import com.bitchat.android.model.AuthenticatedPeerState
import com.bitchat.android.model.PeerCapabilities
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
    fun `authenticated Ed key and capabilities persist rotate and clear atomically`() {
        val firstKey = ByteArray(32) { 0x11 }
        val rotatedKey = ByteArray(32) { 0x22 }
        assertTrue(manager.storeAuthenticatedPeerState(
            fingerprint,
            AuthenticatedPeerState(PeerCapabilities.PRIVATE_MEDIA, firstKey)
        ))

        val reloaded = SecureIdentityStateManager(prefs, testOnly = true)
        assertArrayEquals(firstKey, reloaded.getAuthenticatedSigningKey(fingerprint))
        assertEquals(
            PeerCapabilities.PRIVATE_MEDIA,
            reloaded.getAuthenticatedPeerState(fingerprint)?.capabilities
        )
        assertTrue(reloaded.isPrivateMediaCapable(fingerprint))

        assertTrue(reloaded.storeAuthenticatedPeerState(
            fingerprint,
            AuthenticatedPeerState(PeerCapabilities.NONE, rotatedKey)
        ))
        assertArrayEquals(rotatedKey, reloaded.getAuthenticatedSigningKey(fingerprint))
        // HSTS-style private-media history is not erased by a no-bit proof.
        assertTrue(reloaded.isPrivateMediaCapable(fingerprint))

        reloaded.clearIdentityData()
        assertFalse(manager.storeAuthenticatedPeerState(
            fingerprint,
            AuthenticatedPeerState(PeerCapabilities.PRIVATE_MEDIA, firstKey)
        ))
        val afterPanic = SecureIdentityStateManager(prefs, testOnly = true)
        assertEquals(null, afterPanic.getAuthenticatedPeerState(fingerprint))
        assertFalse(afterPanic.isPrivateMediaCapable(fingerprint))
    }
}
