package com.bitchat.android.nostr

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.services.TestMeshStores
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Upstream #1096 caches the account identity and derived geohash identities in the bridge. On this
 * branch the bridge is identity-scoped, so a panic (which wipes the stored keys and ends the
 * identity) leaves those caches behind with the old bridge: the next identity's bridge starts
 * empty and derives fresh identities. Upstream's `clearAllAssociations` is not needed here.
 */
@RunWith(RobolectricTestRunner::class)
class NostrIdentityWipeRobolectricTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val favorites = TestMeshStores(app).favorites

    @After
    fun tearDown() {
        wipeStoredKeys()
    }

    @Test
    fun `the next identity's bridge caches nothing from the wiped one and derives fresh identities`() {
        wipeStoredKeys()
        val wiped = NostrIdentityBridge(favorites)
        assertNull(wiped.getCachedIdentity(GEOHASH))

        val beforeAccount = wiped.getCurrentNostrIdentity(app)!!
        val beforeGeo = wiped.deriveIdentity(GEOHASH, app)
        assertEquals(beforeGeo, wiped.getCachedIdentity(GEOHASH))
        val secure = SecureIdentityStateManager(app)
        assertEquals(beforeAccount.privateKeyHex, secure.getSecureValue(NOSTR_PRIVATE_KEY))
        assertNotNull(secure.getSecureValue(DEVICE_SEED_KEY))

        assertTrue(wipeStoredKeys())
        val next = NostrIdentityBridge(favorites)

        assertNull(next.getCachedIdentity(GEOHASH))
        val afterAccount = next.getCurrentNostrIdentity(app)!!
        val afterGeo = next.deriveIdentity(GEOHASH, app)
        assertNotEquals(beforeAccount.privateKeyHex, afterAccount.privateKeyHex)
        assertNotEquals(beforeGeo.privateKeyHex, afterGeo.privateKeyHex)
        assertEquals(afterAccount.privateKeyHex, SecureIdentityStateManager(app).getSecureValue(NOSTR_PRIVATE_KEY))
    }

    private fun wipeStoredKeys(): Boolean =
        SecureIdentityStateManager(app).clearSecureValuesSynchronously(NOSTR_PRIVATE_KEY, DEVICE_SEED_KEY)

    private companion object {
        const val NOSTR_PRIVATE_KEY = "nostr_private_key"
        const val DEVICE_SEED_KEY = "nostr_device_seed"
        // Synthetic geohash, not a real location of anyone's.
        const val GEOHASH = "u4pruydq"
    }
}
