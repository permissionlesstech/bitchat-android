package com.bitchat.watch.ui

import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.services.TestMeshStores
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

class WearPeerIdentityStateTest {

    @Test
    fun `favorite indicator matches Android three-state star`() {
        assertEquals(
            FavoriteIndicator.None,
            favoriteIndicator(isFavorite = false, theyFavoritedUs = false)
        )
        assertEquals(
            FavoriteIndicator.FavoritedUs,
            favoriteIndicator(isFavorite = false, theyFavoritedUs = true)
        )
        assertEquals(
            FavoriteIndicator.Favorite,
            favoriteIndicator(isFavorite = true, theyFavoritedUs = false)
        )
        assertEquals(
            FavoriteIndicator.Favorite,
            favoriteIndicator(isFavorite = true, theyFavoritedUs = true)
        )
    }

    @Test
    fun `verification code keeps every fingerprint character`() {
        val fingerprint = (0 until 64).joinToString("") { (it % 16).toString(16) }

        val formatted = formatVerificationCode(fingerprint)

        assertEquals(fingerprint.uppercase(), formatted.filterNot(Char::isWhitespace))
        assertEquals(8, formatted.lines().size)
        assertEquals(List(8) { 2 }, formatted.lines().map { it.split(" ").size })
    }
}

@RunWith(RobolectricTestRunner::class)
class WearPeerIdentityStateMeshRolesTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val state = WearPeerIdentityState(TestMeshStores(context).favorites)
    private val mesh = FakeWearMesh()

    @Test
    fun myFingerprint_readsTheMeshPeersRole() {
        mesh.identity = "ab".repeat(32)

        assertEquals("ab".repeat(32), state.myFingerprint(mesh))
        assertNull(state.myFingerprint(null))
    }

    @Test
    fun snapshot_takesTheFingerprintFromTheMeshPeersRole() {
        state.initialize(context)
        mesh.fingerprint = "cd".repeat(32)

        val snapshot = state.snapshot("bbbb1111bbbb1111", mesh)

        assertEquals("cd".repeat(32), snapshot.fingerprint)
        assertEquals(FavoriteIndicator.None, snapshot.favoriteIndicator)
    }
}
