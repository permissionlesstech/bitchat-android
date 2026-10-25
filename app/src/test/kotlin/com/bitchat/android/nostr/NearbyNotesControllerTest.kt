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

    private val pendingReleases = mutableListOf<Pair<Long, () -> Unit>>()

    private fun runPending() {
        val due = pendingReleases.toList()
        pendingReleases.clear()
        due.forEach { (_, action) -> action() }
    }

    /** Foreground, revealed and holding "s0000000", with releases held until [runPending]. */
    private fun graceController() = NearbyNotesController(
        subscribe = subscriptions::add,
        unsubscribe = { unsubscribeCount += 1 },
        scheduleRelease = { delayMs, action ->
            val entry = delayMs to action
            pendingReleases += entry
            { pendingReleases.remove(entry) }
        },
    ).also {
        it.updateAppForeground(true)
        it.updateAvailability(true, true, "s0000000")
        it.reveal()
        it.activate()
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

    @Test
    fun `rebuild within the grace keeps the subscription`() {
        val controller = graceController()

        controller.deactivate()
        controller.activate()
        runPending()

        assertEquals(0, unsubscribeCount)
        assertEquals(listOf("s0000000"), subscriptions)
    }

    @Test
    fun `leaving closes after the grace exactly once`() {
        val controller = graceController()

        controller.deactivate()
        assertEquals(0, unsubscribeCount)
        assertEquals(listOf(NearbyNotesController.RELEASE_GRACE_MS), pendingReleases.map { it.first })

        runPending()
        controller.deactivate()
        runPending()

        assertEquals(1, unsubscribeCount)
    }

    @Test
    fun `backgrounding during the grace closes immediately`() {
        val controller = graceController()

        controller.deactivate()
        controller.updateAppForeground(false)
        assertEquals(1, unsubscribeCount)

        controller.updateAppForeground(true)
        runPending()

        assertEquals(1, unsubscribeCount)
        assertEquals(listOf("s0000000"), subscriptions)
    }

    @Test
    fun `location off during the grace closes immediately and does not reopen`() {
        val controller = graceController()

        controller.deactivate()
        controller.updateAvailability(false, true, "s0000000")
        assertEquals(1, unsubscribeCount)

        controller.updateAvailability(true, true, "s0000000")
        runPending()

        assertEquals(1, unsubscribeCount)
        assertEquals(listOf("s0000000"), subscriptions)
    }

    @Test
    fun `cell change during the grace releases the old cell immediately`() {
        val controller = graceController()

        controller.deactivate()
        controller.updateAvailability(true, true, "s0000001")
        assertEquals(1, unsubscribeCount)

        runPending()

        assertEquals(1, unsubscribeCount)
        assertEquals(listOf("s0000000"), subscriptions)
    }
}
