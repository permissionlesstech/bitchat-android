package com.bitchat.android.favorites

import android.content.Context
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.services.ContactIdentityResolver
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class FavoritesPersistenceTest {
    @Test fun `legacy relationships load without new control fields`() {
        val context = RuntimeEnvironment.getApplication()
        val secure = SecureIdentityStateManager(context.getSharedPreferences("legacy-${UUID.randomUUID()}", Context.MODE_PRIVATE), true)
        val key = ByteArray(32) { 1 }
        val hex = ContactIdentityResolver.noiseKeyHex(key)
        secure.storeSecureValueAndWait("favorite_relationships", """{"$hex":{
            "peerNoisePublicKeyHex":"$hex","peerNickname":"Synthetic contact",
            "isFavorite":true,"theyFavoritedUs":true,"favoritedAt":1,"lastUpdated":1
        }}""")
        val favorites = FavoritesPersistenceService(context, secure)
        assertTrue(favorites.getFavoriteStatus(key)!!.isMutual)
        assertEquals("", favorites.getFavoriteStatus(key)!!.peerUpdateID)
        favorites.updateFavoriteStatus(key, "Synthetic contact", false)
        val pending = favorites.getFavoriteStatus(key)!!
        assertNotNull(pending.pendingControlID)
        assertTrue(pending.pendingControlTimestamp > 0)
        val reopened = FavoritesPersistenceService(context, secure).getFavoriteStatus(key)!!
        assertEquals(pending.pendingControlID, reopened.pendingControlID)
        assertEquals(pending.pendingControlTimestamp, reopened.pendingControlTimestamp)
    }
}
