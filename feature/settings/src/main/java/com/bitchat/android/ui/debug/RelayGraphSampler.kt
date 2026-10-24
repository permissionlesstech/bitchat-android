package com.bitchat.android.ui.debug

/** What the relay graph plots: everything, or one stacked series per device or per peer. */
internal enum class GraphMode { OVERALL, PER_DEVICE, PER_PEER }

/** Per-key packet counts for the last second, one map per direction. */
internal data class RelayCounts(
    val incoming: Map<String, Int> = emptyMap(),
    val outgoing: Map<String, Int> = emptyMap(),
)

/**
 * The relay graph's series. Each series is the last [RelayGraphSampler.WINDOW]
 * samples, oldest first; [keysIncoming]/[keysOutgoing] are the stacked keys, sorted.
 */
internal data class RelayGraphSeries(
    val overallIncoming: List<Float> = zeros(),
    val overallOutgoing: List<Float> = zeros(),
    val keysIncoming: List<String> = emptyList(),
    val keysOutgoing: List<String> = emptyList(),
    val stackedIncoming: Map<String, List<Float>> = emptyMap(),
    val stackedOutgoing: Map<String, List<Float>> = emptyMap(),
)

private fun zeros(): List<Float> = List(RelayGraphSampler.WINDOW) { 0f }

/**
 * The relay graph's sampling (inventory D14, D15, D19, D20), called once a
 * second by the relay card. Overall appends the last-second totals; the
 * stacked modes advance one series per key and drop series that are all zero.
 *
 * Switching mode resets nothing (QD6): stacked series of the other mode linger
 * until they decay, and the overall series freeze while a stacked mode runs.
 *
 * Colours: the first time a key is drawn it takes the palette slot of its
 * position among the keys drawn with it, and keeps it; past the palette it
 * takes a hue from its hash.
 */
internal class RelayGraphSampler(
    private val paletteSize: Int = 10,
    initial: RelayGraphSeries = RelayGraphSeries(),
) {
    var series: RelayGraphSeries = initial
        private set

    // Key to palette slot, or NO_SLOT for a key that takes its hash hue.
    private val slots = mutableMapOf<String, Int>()

    fun sample(mode: GraphMode, stats: PacketRelayStats, perDevice: RelayCounts, perPeer: RelayCounts) {
        series = when (mode) {
            GraphMode.OVERALL -> series.copy(
                overallIncoming = (series.overallIncoming + stats.lastSecondIncoming.toFloat()).takeLast(WINDOW),
                overallOutgoing = (series.overallOutgoing + stats.lastSecondOutgoing.toFloat()).takeLast(WINDOW),
            )
            GraphMode.PER_DEVICE -> advanceStacked(perDevice)
            GraphMode.PER_PEER -> advanceStacked(perPeer)
        }
        if (mode != GraphMode.OVERALL) {
            // The card draws Incoming, then Outgoing, and colours each block's keys as it draws.
            assignSlots(series.keysIncoming)
            assignSlots(series.keysOutgoing)
        }
    }

    /** The palette slot [key] was given, or [NO_SLOT] when it takes [fallbackHue]. */
    fun colorIndexFor(key: String): Int = slots[key] ?: NO_SLOT

    /** The graph's top label: the highest bar in [mode], for one direction. */
    fun topLabel(mode: GraphMode, incoming: Boolean): Int {
        val max = when (mode) {
            GraphMode.OVERALL -> (if (incoming) series.overallIncoming else series.overallOutgoing).maxOrNull()
            else -> stackedTotals(if (incoming) series.stackedIncoming else series.stackedOutgoing).maxOrNull()
        }
        return (max ?: 0f).toInt()
    }

    private fun advanceStacked(counts: RelayCounts): RelayGraphSeries {
        val incoming = advance(series.stackedIncoming, counts.incoming).filterValues { s -> s.any { it != 0f } }
        val outgoing = advance(series.stackedOutgoing, counts.outgoing).filterValues { s -> s.any { it != 0f } }
        return series.copy(
            stackedIncoming = incoming,
            stackedOutgoing = outgoing,
            keysIncoming = incoming.keys.sorted(),
            keysOutgoing = outgoing.keys.sorted(),
        )
    }

    private fun advance(base: Map<String, List<Float>>, snap: Map<String, Int>): Map<String, List<Float>> {
        val next = mutableMapOf<String, List<Float>>()
        (base.keys + snap.keys).toSet().forEach { k ->
            val prev = base[k] ?: zeros()
            next[k] = (prev + (snap[k] ?: 0).toFloat()).takeLast(WINDOW)
        }
        return next
    }

    private fun assignSlots(keys: List<String>) {
        keys.forEachIndexed { idx, k ->
            slots.putIfAbsent(k, if (idx < paletteSize) idx else NO_SLOT)
        }
    }

    companion object {
        const val WINDOW = 60
        const val NO_SLOT = -1

        /** Each sample's total across [stacked] series, for the [WINDOW] samples. */
        fun stackedTotals(stacked: Map<String, List<Float>>): List<Float> =
            (0 until WINDOW).map { idx -> stacked.values.sumOf { it.getOrNull(idx)?.toDouble() ?: 0.0 }.toFloat() }

        /** The hue, in degrees, of a key that has no palette slot. */
        fun fallbackHue(key: String): Float = ((key.hashCode().toUInt().toInt() and 0x7FFFFFFF) % 360).toFloat()
    }
}
