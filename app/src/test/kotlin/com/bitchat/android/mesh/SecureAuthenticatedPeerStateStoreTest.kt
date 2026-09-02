package com.bitchat.android.mesh

import android.content.Context
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.model.AuthenticatedPeerState
import com.bitchat.android.model.PeerCapabilities
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.UUID

/**
 * Pins the persisted lookups the message path relies on for senders the live
 * registry has forgotten: the store must resolve a peer ID to its persisted
 * fingerprint, and a fingerprint to its persisted nickname, through the same
 * records the app already keeps.
 */
@RunWith(RobolectricTestRunner::class)
class SecureAuthenticatedPeerStateStoreTest {

    private val fingerprint = "ab".repeat(32)
    private val peerID = fingerprint.take(16)
    private val signingKey = ByteArray(32) { 0x5A }

    private fun freshStore(): Pair<SecureAuthenticatedPeerStateStore, SecureIdentityStateManager> {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("store-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val identity = SecureIdentityStateManager(prefs, testOnly = true)
        return SecureAuthenticatedPeerStateStore(context, identity) to identity
    }

    @Test
    fun `a departed peer resolves by peer ID to its persisted state and nickname`() {
        val (store, identity) = freshStore()
        identity.cacheFingerprintNickname(fingerprint, "relay-a")
        identity.storeAuthenticatedPeerState(fingerprint, AuthenticatedPeerState(PeerCapabilities.NONE, signingKey))

        assertEquals(fingerprint, store.persistedFingerprintFor(peerID))
        assertEquals("relay-a", store.persistedNickname(fingerprint))
        assertArrayEquals(signingKey, store.load(fingerprint)?.signingPublicKey)
    }

    @Test
    fun `a peer this device never persisted resolves to nothing`() {
        val (store, identity) = freshStore()
        identity.cacheFingerprintNickname(fingerprint, "relay-a")

        assertNull("a cached nickname is not a persisted identity", store.persistedFingerprintFor(peerID))
        assertNull(store.load(fingerprint))
    }
}
