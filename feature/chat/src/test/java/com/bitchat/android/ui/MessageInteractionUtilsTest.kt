package com.bitchat.android.ui

import com.bitchat.android.geohash.GeohashChannelLevel
import com.bitchat.android.model.BitchatMessage
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageInteractionUtilsTest {
    @Test
    fun `self detection accepts peer id nickname and nickname suffix`() {
        assertTrue(message(sender = "alice", senderPeerId = "peer-a").isFromSelf("me", "peer-a"))
        assertTrue(message(sender = "me").isFromSelf("me", "peer-a"))
        assertTrue(message(sender = "me#1a2b").isFromSelf("me", "peer-a"))
    }

    @Test
    fun `self detection rejects unrelated sender`() {
        assertFalse(message(sender = "alice", senderPeerId = "peer-b").isFromSelf("me", "peer-a"))
    }

    @Test
    fun `URL normalization preserves explicit HTTP schemes`() {
        assertEquals("http://example.com", normalizeMessageUrl("http://example.com"))
        assertEquals("HTTPS://example.com", normalizeMessageUrl("HTTPS://example.com"))
    }

    @Test
    fun `URL normalization defaults bare URLs to HTTPS`() {
        assertEquals("https://example.com", normalizeMessageUrl("example.com"))
    }

    @Test
    fun `geohash channel precision matches navigation levels`() {
        val expectedLevels = mapOf(
            "9q" to GeohashChannelLevel.REGION,
            "9q8" to GeohashChannelLevel.PROVINCE,
            "9q8y" to GeohashChannelLevel.PROVINCE,
            "9q8yy" to GeohashChannelLevel.CITY,
            "9q8yyk" to GeohashChannelLevel.NEIGHBORHOOD,
            "9q8yyk8" to GeohashChannelLevel.BLOCK,
        )

        expectedLevels.forEach { (geohash, level) ->
            assertEquals(level, channelForGeohash(geohash).level)
        }
    }

    @Test
    fun `geohash channel normalizes casing`() {
        assertEquals("9q8yy", channelForGeohash("9Q8YY").geohash)
    }

    private fun message(sender: String, senderPeerId: String? = null): BitchatMessage =
        BitchatMessage(
            sender = sender,
            content = "hello",
            timestamp = Date(0),
            senderPeerID = senderPeerId,
        )
}
