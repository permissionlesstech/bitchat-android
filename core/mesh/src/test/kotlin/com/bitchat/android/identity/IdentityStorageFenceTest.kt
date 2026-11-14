package com.bitchat.android.identity

import android.content.Context
import com.bitchat.android.model.AuthenticatedPeerState
import com.bitchat.android.model.PeerCapabilities
import java.util.UUID
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * A panic wipes the identity's storage by location and no longer through a manager, so it fences
 * every manager that exists before the wipe on its own: a write still queued on one of them must
 * not bring the old identity's state back into the storage the next identity starts on.
 */
@RunWith(RobolectricTestRunner::class)
class IdentityStorageFenceTest {
    private val prefs = RuntimeEnvironment.getApplication()
        .getSharedPreferences("identity-fence-${UUID.randomUUID()}", Context.MODE_PRIVATE)
    private val fingerprint = "cd".repeat(32)
    private val peer = SecureIdentityStateManager.CachedPeerIdentity(
        "fixture", "1".padStart(64, '0'), "2".padStart(64, '0'), "fixture-name"
    )

    @Test
    fun aManagerFromBeforeTheFenceWritesNothing() {
        val old = SecureIdentityStateManager(prefs, testOnly = true)
        val queuedGeneration = old.cacheGeneration()

        SecureIdentityStateManager.fenceEarlierInstances()

        assertFalse(old.storeAuthenticatedPeerState(fingerprint, AuthenticatedPeerState(PeerCapabilities.PRIVATE_MEDIA, ByteArray(32) { 0x11 })))
        old.cachePeerIdentities(listOf(peer), queuedGeneration)
        assertEquals(emptyMap<String, Any?>(), prefs.all)
        assertNull(old.getAuthenticatedPeerState(fingerprint))
    }

    @Test
    fun aManagerFromBeforeTheFenceStoresNoValueEither() {
        val old = SecureIdentityStateManager(prefs, testOnly = true)

        SecureIdentityStateManager.fenceEarlierInstances()

        old.storeSecureValue("synthetic_key", "synthetic value")
        assertFalse(old.clearSecureValuesSynchronously("synthetic_key"))
        assertEquals(emptyMap<String, Any?>(), prefs.all)
    }

    @Test
    fun aWriteFromAnotherThreadIsRefusedWhileAPanicHoldsTheStorage() {
        val manager = SecureIdentityStateManager(prefs, testOnly = true)
        val elsewhere = Executors.newSingleThreadExecutor()
        try {
            SecureIdentityStateManager.excludingOtherThreads {
                elsewhere.submit { manager.storeSecureValue("from_elsewhere", "synthetic") }.get()
                // The panic's own thread still writes: it builds the next identity in here.
                manager.storeSecureValue("from_the_panic", "synthetic")
            }
            assertNull(manager.getSecureValue("from_elsewhere"))
            assertEquals("synthetic", manager.getSecureValue("from_the_panic"))

            elsewhere.submit { manager.storeSecureValue("from_elsewhere", "synthetic") }.get()
            assertEquals("synthetic", manager.getSecureValue("from_elsewhere"))
        } finally {
            elsewhere.shutdown()
        }
    }

    @Test
    fun aManagerFromAfterTheFenceWorks() {
        SecureIdentityStateManager.fenceEarlierInstances()
        val next = SecureIdentityStateManager(prefs, testOnly = true)

        assertTrue(next.storeAuthenticatedPeerState(fingerprint, AuthenticatedPeerState(PeerCapabilities.PRIVATE_MEDIA, ByteArray(32) { 0x22 })))
        assertTrue(next.isPrivateMediaCapable(fingerprint))
        next.cachePeerIdentities(listOf(peer))
        assertEquals(peer.fingerprint, next.getCachedPeerFingerprint(peer.peerID))
    }
}
