package com.bitchat.android.ui

import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.geohash.GeohashChannel
import com.bitchat.android.geohash.GeohashChannelLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocationChannelsSheetTest {

    @Test
    fun `teleported channel gets a standalone row`() {
        val nearby = listOf(channel("u33dc"))
        val teleported = channel("dr5ru")

        assertEquals(
            teleported,
            selectedLocationChannelOutsideNearby(ChannelID.Location(teleported), nearby)
        )
    }

    @Test
    fun `nearby selected channel is not duplicated`() {
        val nearby = channel("u33dc")

        assertNull(
            selectedLocationChannelOutsideNearby(
                ChannelID.Location(channel("U33DC")),
                listOf(nearby)
            )
        )
    }

    @Test
    fun `mesh selection has no standalone location row`() {
        assertNull(
            selectedLocationChannelOutsideNearby(
                ChannelID.Mesh,
                listOf(channel("u33dc"))
            )
        )
    }

    private fun channel(geohash: String) = GeohashChannel(
        level = GeohashChannelLevel.CITY,
        geohash = geohash
    )
}
