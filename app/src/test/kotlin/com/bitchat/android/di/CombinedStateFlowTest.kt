package com.bitchat.android.di

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the combined flows Debug's port is built from: their value is always the
 * sources' current values, as when Debug read each source itself, and D2's
 * discovered-peer nicknames.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CombinedStateFlowTest {

    private val peerA = "0123456789abcdef"
    private val peerB = "fedcba9876543210"

    // D2
    @Test
    fun discoveredPeers_takeTheirKnownNickname_orBlank() {
        assertEquals(
            mapOf(peerA to "alice", peerB to ""),
            discoveredWithNicknames(setOf(peerA, peerB), mapOf(peerA to "alice")),
        )
        assertEquals(emptyMap<String, String>(), discoveredWithNicknames(emptySet(), mapOf(peerA to "alice")))
    }

    @Test
    fun value_readsTheSourcesNow_withNoCollector() {
        val discovered = MutableStateFlow(setOf(peerA))
        val known = MutableStateFlow(emptyMap<String, String>())
        val combined = combineStates(discovered, known, ::discoveredWithNicknames)

        assertEquals(mapOf(peerA to ""), combined.value)
        known.value = mapOf(peerA to "alice")
        assertEquals(mapOf(peerA to "alice"), combined.value)
        assertEquals(listOf(combined.value), combined.replayCache)
    }

    @Test
    fun collecting_emitsTheCurrentValue_thenEachDistinctChange() = runTest(UnconfinedTestDispatcher()) {
        val a = MutableStateFlow(false)
        val b = MutableStateFlow(1)
        val c = MutableStateFlow("x")
        val d = MutableStateFlow<String?>(null)
        val combined = combineStates(a, b, c, d) { w, x, y, z -> "$w/$x/$y/$z" }
        val seen = mutableListOf<String>()

        val job = launch { combined.collect { seen += it } }
        a.value = true
        b.value = 2
        d.value = "why"
        job.cancel()

        assertEquals(listOf("false/1/x/null", "true/1/x/null", "true/2/x/null", "true/2/x/why"), seen)
    }

    @Test
    fun collecting_skipsAChangeThatLeavesTheResultEqual() = runTest(UnconfinedTestDispatcher()) {
        val discovered = MutableStateFlow(setOf(peerA))
        val known = MutableStateFlow(emptyMap<String, String>())
        val combined = combineStates(discovered, known, ::discoveredWithNicknames)
        val seen = mutableListOf<Map<String, String>>()

        val job = launch { combined.collect { seen += it } }
        // A nickname for a peer that is not discovered changes nothing shown.
        known.value = mapOf(peerB to "alice")
        job.cancel()

        assertEquals(listOf(mapOf(peerA to "")), seen)
    }
}
