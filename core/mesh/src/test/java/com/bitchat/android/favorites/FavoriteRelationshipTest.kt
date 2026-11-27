package com.bitchat.android.favorites

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class FavoriteRelationshipTest {

    @Test
    fun `first inbound favorite creates a received relationship`() {
        val noiseKey = ByteArray(32) { it.toByte() }

        val relationship = null.withPeerFavoritedUs(
            noisePublicKey = noiseKey,
            theyFavoritedUs = true,
            now = Date(123L)
        )

        assertFalse(relationship.isFavorite)
        assertTrue(relationship.theyFavoritedUs)
        assertTrue(relationship.peerNoisePublicKey.contentEquals(noiseKey))
    }

    @Test
    fun `inbound favorite preserves our existing favorite`() {
        val noiseKey = ByteArray(32) { it.toByte() }
        val existing = FavoriteRelationship(
            peerNoisePublicKey = noiseKey,
            peerNostrPublicKey = null,
            peerNickname = "peer",
            isFavorite = true,
            theyFavoritedUs = false,
            favoritedAt = Date(10L),
            lastUpdated = Date(20L)
        )

        val relationship = existing.withPeerFavoritedUs(
            noisePublicKey = noiseKey,
            theyFavoritedUs = true,
            now = Date(30L)
        )

        assertTrue(relationship.isFavorite)
        assertTrue(relationship.theyFavoritedUs)
        assertTrue(relationship.isMutual)
    }
}
