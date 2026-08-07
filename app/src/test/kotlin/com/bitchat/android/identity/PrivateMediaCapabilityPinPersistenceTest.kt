package com.bitchat.android.identity

import android.content.Context
import android.content.SharedPreferences
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

    @Test
    fun `identity panic wipe preserves NDR protection until native reset succeeds`() {
        val protected = mapOf(
            "favorite_relationships" to """{"contact":"pinned"}""",
            "favorite_peerid_index" to """{"peer":"npub"}""",
            "favorite_ndr_rebind_v1" to """{"version":1}"""
        )
        protected.forEach { (key, value) ->
            assertTrue(manager.storeSecureValueSynchronously(key, value))
        }
        assertTrue(manager.storeSecureValueSynchronously("unrelated_secret", "remove-me"))

        assertTrue(manager.clearIdentityData())

        protected.forEach { (key, value) ->
            assertEquals(value, manager.getSecureValue(key))
        }
        assertEquals(null, manager.getSecureValue("unrelated_secret"))
    }

    @Test
    fun `identity panic wipe reports a failed durable commit`() {
        val failingPreferences = CommitFailingSharedPreferences(prefs)
        val failingManager = SecureIdentityStateManager(failingPreferences, testOnly = true)
        assertTrue(failingManager.storeSecureValueSynchronously("unrelated_secret", "keep"))

        failingPreferences.failCommits = true

        assertFalse(failingManager.clearIdentityData())
        assertEquals("keep", failingManager.getSecureValue("unrelated_secret"))
    }

    private class CommitFailingSharedPreferences(
        private val delegate: SharedPreferences
    ) : SharedPreferences by delegate {
        var failCommits = false

        override fun edit(): SharedPreferences.Editor =
            CommitFailingEditor(delegate.edit())

        private inner class CommitFailingEditor(
            private val delegateEditor: SharedPreferences.Editor
        ) : SharedPreferences.Editor by delegateEditor {
            override fun clear(): SharedPreferences.Editor {
                delegateEditor.clear()
                return this
            }

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                delegateEditor.putString(key, value)
                return this
            }

            override fun commit(): Boolean =
                if (failCommits) false else delegateEditor.commit()
        }
    }
}
