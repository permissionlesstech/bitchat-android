package com.bitchat.watch.ui

import org.junit.Assert.assertEquals
import org.junit.Test

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
        assertEquals(4, formatted.lines().size)
        assertEquals(listOf(4, 4, 4, 4), formatted.lines().map { it.split(" ").size })
    }
}
