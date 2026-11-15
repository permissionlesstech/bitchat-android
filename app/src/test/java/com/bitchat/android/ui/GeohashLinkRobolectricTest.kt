package com.bitchat.android.ui

import android.net.Uri
import com.bitchat.android.geohash.GeohashChannelLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeohashLinkRobolectricTest {
    @Test
    fun `geohash link selects the channel it names`() {
        val channel = channelForGeohashLink(Uri.parse("bitchat://geohash/9q8yyk8"))

        assertEquals("9q8yyk8", channel?.geohash)
        assertEquals(GeohashChannelLevel.BLOCK, channel?.level)
    }

    @Test
    fun `geohash link level follows geohash length`() {
        val expectedLevels = mapOf(
            "9q" to GeohashChannelLevel.REGION,
            "9q8" to GeohashChannelLevel.PROVINCE,
            "9q8y" to GeohashChannelLevel.PROVINCE,
            "9q8yy" to GeohashChannelLevel.CITY,
            "9q8yyk" to GeohashChannelLevel.NEIGHBORHOOD,
            "9q8yyk8" to GeohashChannelLevel.BLOCK,
            "9q8yyk8y" to GeohashChannelLevel.BUILDING,
            "9q8yyk8ytpxr" to GeohashChannelLevel.BUILDING,
        )

        expectedLevels.forEach { (geohash, level) ->
            assertEquals(geohash, level, channelForGeohashLink(Uri.parse("bitchat://geohash/$geohash"))?.level)
        }
    }

    @Test
    fun `geohash link is normalized like the iOS client`() {
        assertEquals("9q8yy", channelForGeohashLink(Uri.parse("bitchat://geohash/9Q8YY"))?.geohash)
        assertEquals("9q8yy", channelForGeohashLink(Uri.parse("bitchat://geohash/9q8yy/"))?.geohash)
    }

    @Test
    fun `malformed geohash links select nothing`() {
        val rejected = listOf(
            "bitchat://geohash",
            "bitchat://geohash/",
            "bitchat://geohash/9",
            "bitchat://geohash/9q8yyk8ytpxrz",
            "bitchat://geohash/9q8ayy",
            "bitchat://geohash/9q/8y",
            "bitchat://geohash/9q%238y",
            "bitchat://geohash/%209q8y",
        )

        rejected.forEach { link -> assertNull(link, channelForGeohashLink(Uri.parse(link))) }
    }

    @Test
    fun `links for other destinations select nothing`() {
        assertNull(channelForGeohashLink(Uri.parse("bitchat://verify/9q8yy")))
        assertNull(channelForGeohashLink(Uri.parse("https://geohash/9q8yy")))
    }
}
