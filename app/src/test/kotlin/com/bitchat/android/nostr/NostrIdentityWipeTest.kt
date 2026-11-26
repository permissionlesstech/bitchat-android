package com.bitchat.android.nostr

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.identity.SecureIdentityStateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NostrIdentityWipeTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `clearAllAssociations wipes independent storage and cache and derives fresh identities`() {
        NostrIdentityBridge.clearAllAssociations(app)
        try {
            val secure = SecureIdentityStateManager(app)
            assertNull(NostrIdentityBridge.getCachedIdentity("u4pruydq"))

            val beforeAccount = NostrIdentityBridge.getCurrentNostrIdentity(app)!!
            val beforeGeo = NostrIdentityBridge.deriveIdentity("u4pruydq", app)
            assertEquals(beforeGeo, NostrIdentityBridge.getCachedIdentity("u4pruydq"))

            // Verify persisted directly in independent SecureIdentityStateManager storage
            assertEquals(beforeAccount.privateKeyHex, secure.getSecureValue("nostr_private_key"))
            assertNotNull(secure.getSecureValue("nostr_device_seed"))

            assertTrue(NostrIdentityBridge.clearAllAssociations(app))

            // Both independent storage and lock-free cache must be cleared immediately
            assertNull(secure.getSecureValue("nostr_private_key"))
            assertNull(secure.getSecureValue("nostr_device_seed"))
            assertNull(NostrIdentityBridge.getCachedIdentity("u4pruydq"))

            val afterAccount = NostrIdentityBridge.getCurrentNostrIdentity(app)!!
            val afterGeo = NostrIdentityBridge.deriveIdentity("u4pruydq", app)
            assertNotEquals(beforeAccount.privateKeyHex, afterAccount.privateKeyHex)
            assertNotEquals(beforeGeo.privateKeyHex, afterGeo.privateKeyHex)
            assertEquals(afterAccount.privateKeyHex, secure.getSecureValue("nostr_private_key"))
        } finally {
            NostrIdentityBridge.clearAllAssociations(app)
        }
    }
}
