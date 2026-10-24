package com.bitchat.android.ui.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the relay graph's sampling (inventory D14, D15, D19, D20, QD6), driven
 * the way the relay card's loop drives it: one sample per tick, in the card's mode.
 */
class RelayGraphSamplerTest {

    private val peerA = "0123456789abcdef"
    private val peerB = "fedcba9876543210"
    private val device = "00:11:22:33:44:55"

    private fun stats(incoming: Int = 0, outgoing: Int = 0) =
        PacketRelayStats(lastSecondIncoming = incoming, lastSecondOutgoing = outgoing)

    private fun RelayGraphSampler.tick(
        mode: GraphMode,
        stats: PacketRelayStats = stats(),
        perDevice: RelayCounts = RelayCounts(),
        perPeer: RelayCounts = RelayCounts(),
    ) = sample(mode, stats, perDevice, perPeer)

    // D14
    @Test
    fun overall_startsAtSixtyZeros_appendsLastSecond_keepsSixty() {
        val sampler = RelayGraphSampler()
        assertEquals(List(60) { 0f }, sampler.series.overallIncoming)

        repeat(61) { i -> sampler.tick(GraphMode.OVERALL, stats(incoming = i + 1, outgoing = 2 * (i + 1))) }

        assertEquals((2..61).map { it.toFloat() }, sampler.series.overallIncoming)
        assertEquals((2..61).map { 2f * it }, sampler.series.overallOutgoing)
    }

    // D15
    @Test
    fun stacked_newKeyStartsFromZeros_missingSampleIsZero_droppedOnceAllZero() {
        val sampler = RelayGraphSampler()
        sampler.tick(GraphMode.PER_PEER, perPeer = RelayCounts(incoming = mapOf(peerB to 3, peerA to 1)))

        val first = sampler.series
        assertEquals(listOf(peerA, peerB), first.keysIncoming)
        assertEquals(List(59) { 0f } + 3f, first.stackedIncoming[peerB])
        assertTrue(first.keysOutgoing.isEmpty())

        // The 3 stays in the window for 59 more samples, then the series is all zero and goes.
        repeat(59) { sampler.tick(GraphMode.PER_PEER) }
        assertEquals(listOf(peerA, peerB), sampler.series.keysIncoming)
        assertEquals(listOf(3f) + List(59) { 0f }, sampler.series.stackedIncoming[peerB])
        sampler.tick(GraphMode.PER_PEER)
        assertTrue(sampler.series.keysIncoming.isEmpty())
        assertTrue(sampler.series.stackedIncoming.isEmpty())
    }

    // QD6
    @Test
    fun modeSwitch_keepsOldSeries_currentBehaviour_QD6() {
        val sampler = RelayGraphSampler()
        sampler.tick(GraphMode.OVERALL, stats(incoming = 5))
        sampler.tick(GraphMode.PER_DEVICE, perDevice = RelayCounts(incoming = mapOf(device to 2)))
        val overallBefore = sampler.series.overallIncoming

        sampler.tick(GraphMode.PER_PEER, stats(incoming = 9), perPeer = RelayCounts(incoming = mapOf(peerA to 1)))

        // The device-keyed series lingers in Per Peer mode, next to the peer's.
        assertEquals(listOf(device, peerA).sorted(), sampler.series.keysIncoming)
        assertEquals(List(58) { 0f } + listOf(2f, 0f), sampler.series.stackedIncoming[device])
        // The legend then labels the address as if it were a peer ID.
        assertEquals(
            "00:11:",
            legendTitle(GraphMode.PER_PEER, device, emptyMap(), emptyList(), emptyMap()),
        )
        // Overall froze while a stacked mode ran, and resumes from its stale history.
        assertEquals(overallBefore, sampler.series.overallIncoming)
        sampler.tick(GraphMode.OVERALL, stats(incoming = 7))
        assertEquals(overallBefore.drop(1) + 7f, sampler.series.overallIncoming)
    }

    // D19
    @Test
    fun colours_takeFirstSeenPosition_andKeepIt_incomingBeforeOutgoing() {
        val sampler = RelayGraphSampler()
        // Overall mode draws no stacked keys, so it assigns nothing.
        sampler.tick(GraphMode.OVERALL)
        assertEquals(RelayGraphSampler.NO_SLOT, sampler.colorIndexFor(peerA))

        sampler.tick(GraphMode.PER_PEER, perPeer = RelayCounts(incoming = mapOf(peerB to 1)))
        assertEquals(0, sampler.colorIndexFor(peerB))

        // peerA sorts first, so it is drawn at position 0 and takes slot 0 too; peerB keeps its slot.
        sampler.tick(
            GraphMode.PER_PEER,
            perPeer = RelayCounts(incoming = mapOf(peerA to 1), outgoing = mapOf(device to 1)),
        )
        assertEquals(0, sampler.colorIndexFor(peerA))
        assertEquals(0, sampler.colorIndexFor(peerB))
        // Outgoing keys are coloured by their position among the outgoing keys.
        assertEquals(0, sampler.colorIndexFor(device))
    }

    // D19
    @Test
    fun colours_pastThePalette_takeTheHashHue_andKeepIt() {
        val sampler = RelayGraphSampler(paletteSize = 1)
        sampler.tick(GraphMode.PER_PEER, perPeer = RelayCounts(incoming = mapOf(peerA to 1, peerB to 1)))
        assertEquals(0, sampler.colorIndexFor(peerA))
        assertEquals(RelayGraphSampler.NO_SLOT, sampler.colorIndexFor(peerB))

        // Once peerA is gone peerB is drawn first, but it keeps the hash hue.
        repeat(60) { sampler.tick(GraphMode.PER_PEER, perPeer = RelayCounts(incoming = mapOf(peerB to 1))) }
        assertEquals(listOf(peerB), sampler.series.keysIncoming)
        assertEquals(RelayGraphSampler.NO_SLOT, sampler.colorIndexFor(peerB))

        val hue = RelayGraphSampler.fallbackHue(peerB)
        assertEquals(hue, RelayGraphSampler.fallbackHue(peerB))
        assertTrue(hue >= 0f && hue < 360f)
    }

    // D20
    @Test
    fun topLabel_isTheHighestOverallSample_orTheHighestStackedTotal() {
        val sampler = RelayGraphSampler()
        assertEquals(0, sampler.topLabel(GraphMode.OVERALL, incoming = true))

        sampler.tick(GraphMode.OVERALL, stats(incoming = 4, outgoing = 1))
        sampler.tick(GraphMode.OVERALL, stats(incoming = 2, outgoing = 6))
        assertEquals(4, sampler.topLabel(GraphMode.OVERALL, incoming = true))
        assertEquals(6, sampler.topLabel(GraphMode.OVERALL, incoming = false))

        sampler.tick(GraphMode.PER_PEER, perPeer = RelayCounts(incoming = mapOf(peerA to 3, peerB to 2)))
        sampler.tick(GraphMode.PER_PEER, perPeer = RelayCounts(incoming = mapOf(peerA to 4)))
        assertEquals(5, sampler.topLabel(GraphMode.PER_PEER, incoming = true))
        assertEquals(0, sampler.topLabel(GraphMode.PER_PEER, incoming = false))
    }

    // The saved series come back as they were; the colours start over (QD17).
    @Test
    fun restoredSeries_continue_withNoColours_currentBehaviour_QD17() {
        val sampler = RelayGraphSampler()
        sampler.tick(GraphMode.PER_PEER, perPeer = RelayCounts(incoming = mapOf(peerA to 1)))

        val restored = RelayGraphSampler(initial = sampler.series)
        assertEquals(sampler.series, restored.series)
        assertEquals(RelayGraphSampler.NO_SLOT, restored.colorIndexFor(peerA))

        restored.tick(GraphMode.PER_PEER, perPeer = RelayCounts(incoming = mapOf(peerA to 2)))
        assertEquals(List(58) { 0f } + listOf(1f, 2f), restored.series.stackedIncoming[peerA])
        assertEquals(0, restored.colorIndexFor(peerA))
    }
}
