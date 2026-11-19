package com.bitchat.android.nostr

import com.bitchat.android.geohash.GeohashChannel
import com.bitchat.android.geohash.GeohashChannelLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyNotesControllerTest {
    private val subscriptions = mutableListOf<String>()
    private var unsubscribeCount = 0

    private fun controller() = NearbyNotesController(
        subscribe = subscriptions::add,
        unsubscribe = { unsubscribeCount += 1 },
    )

    private fun foregroundController() = controller().also {
        it.updateAppForeground(true)
    }

    @Test
    fun `active mesh timeline does not subscribe before explicit reveal`() {
        val controller = foregroundController()

        controller.updateAvailability(
            locationEnabled = true,
            locationAuthorized = true,
            buildingGeohash = "u4pruydq",
        )
        controller.activate()

        assertTrue(controller.offersRevealHint())
        assertTrue(subscriptions.isEmpty())

        controller.reveal()

        assertFalse(controller.offersRevealHint())
        assertEquals(listOf("u4pruydq"), subscriptions)
    }

    @Test
    fun `reveal remains dormant until a nearby notes surface is active`() {
        val controller = foregroundController()
        controller.updateAvailability(true, true, "u4pruydq")

        controller.reveal()

        assertTrue(subscriptions.isEmpty())

        controller.activate()

        assertEquals(listOf("u4pruydq"), subscriptions)
    }

    @Test
    fun `last deactivate unsubscribes exactly once`() {
        val controller = foregroundController()
        controller.updateAvailability(true, true, "u4pruydq")
        controller.reveal()
        controller.activate()
        controller.activate()

        controller.deactivate()
        assertEquals(0, unsubscribeCount)

        controller.deactivate()
        controller.deactivate()

        assertEquals(1, unsubscribeCount)
    }

    @Test
    fun `backgrounding closes the subscription and foregrounding restores it`() {
        val controller = foregroundController()
        controller.updateAvailability(true, true, "u4pruydq")
        controller.activate()
        controller.reveal()

        controller.updateAppForeground(false)

        assertEquals(1, unsubscribeCount)
        assertTrue(controller.revealed.value)

        controller.updateAppForeground(false)
        assertEquals(1, unsubscribeCount)

        controller.updateAppForeground(true)
        assertEquals(listOf("u4pruydq", "u4pruydq"), subscriptions)
    }

    @Test
    fun `disable and permission revocation close the live subscription`() {
        val controller = foregroundController()
        controller.updateAvailability(true, true, "u4pruydq")
        controller.activate()
        controller.reveal()

        controller.updateAvailability(false, true, "u4pruydq")
        assertEquals(1, unsubscribeCount)

        controller.updateAvailability(true, true, "u4pruydq")
        assertEquals(listOf("u4pruydq", "u4pruydq"), subscriptions)

        controller.updateAvailability(true, false, "u4pruydq")
        assertEquals(2, unsubscribeCount)
    }

    @Test
    fun `moving building cells releases old subscription before retargeting`() {
        val events = mutableListOf<String>()
        val controller = NearbyNotesController(
            subscribe = { events += "subscribe:$it" },
            unsubscribe = { events += "unsubscribe" },
        )
        controller.updateAppForeground(true)
        controller.updateAvailability(true, true, "u4pruydq")
        controller.activate()
        controller.reveal()

        controller.updateAvailability(true, true, "u4pruydr")

        assertEquals(
            listOf(
                "subscribe:u4pruydq",
                "unsubscribe",
                "subscribe:u4pruydr",
            ),
            events,
        )
    }

    @Test
    fun `building sampling is excluded until reveal while bookmarks remain eligible`() {
        val channels = listOf(
            GeohashChannel(GeohashChannelLevel.BUILDING, "u4pruydq"),
            GeohashChannel(GeohashChannelLevel.BLOCK, "u4pruyd"),
            GeohashChannel(GeohashChannelLevel.CITY, "u4pru"),
        )

        assertEquals(
            listOf("u4pruyd", "u4pru", "saved123"),
            geohashesForSampling(
                availableChannels = channels,
                bookmarks = listOf("saved123"),
                notesRevealed = false,
            ),
        )
        assertEquals(
            listOf("u4pruydq", "u4pruyd", "u4pru", "saved123"),
            geohashesForSampling(
                availableChannels = channels,
                bookmarks = listOf("saved123"),
                notesRevealed = true,
            ),
        )
    }
}
