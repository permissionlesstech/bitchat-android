package com.bitchat.android.nostr

import com.bitchat.android.geohash.LiveLocationPrivacyGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Reopening nearby notes in the same process must show the notes again: the
 * relay manager drops every event id the global deduplicator has already seen,
 * so the notes manager has to forget the ids it showed when it drops its list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LocationNotesReloadTest {

    private val dispatcher = StandardTestDispatcher()
    private val manager = LocationNotesManager.getInstance()
    private val handlers = mutableListOf<(NostrEvent) -> Unit>()
    private val endOfStoredEvents = mutableListOf<() -> Unit>()
    private val geohash = "s0000000"
    private lateinit var note: NostrEvent

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        LiveLocationPrivacyGate.update(true)
        NostrEventDeduplicator.getInstance().clear()
        manager.initialize(
            relayManager = { error("unused") },
            subscribe = { _, id, handler, onEndOfStoredEvents ->
                handlers += handler
                endOfStoredEvents += onEndOfStoredEvents
                id
            },
            unsubscribe = {},
            sendEvent = { _, _, _ -> },
            deriveIdentity = { error("unused") },
        )
        note = syntheticNote("synthetic")
    }

    private fun syntheticNote(content: String): NostrEvent = runBlocking {
        NostrProtocol.createGeohashTextNote(
            content = content,
            geohash = geohash,
            senderIdentity = NostrIdentity.generate(),
            nickname = null,
        )
    }

    @After
    fun tearDown() {
        manager.stop()
        LiveLocationPrivacyGate.update(false)
        NostrEventDeduplicator.getInstance().clear()
        Dispatchers.resetMain()
    }

    /** What NostrRelayManager does with an incoming event before calling the handler. */
    private fun deliver(event: NostrEvent) {
        if (!NostrEventDeduplicator.getInstance().isDuplicate(event.id)) {
            handlers.last()(event)
        }
    }

    @Test
    fun `stop forgets shown note ids so a reload can deliver them again`() {
        val dedup = NostrEventDeduplicator.getInstance()
        manager.setGeohash(geohash)
        deliver(note)
        assertEquals(listOf(note.id), manager.notes.value.map { it.id })
        dedup.isDuplicate("unrelated-id")

        manager.stop()

        assertFalse(dedup.contains(note.id))
        assertTrue(dedup.contains("unrelated-id"))
        manager.setGeohash(geohash)
        deliver(note)
        assertEquals(listOf(note.id), manager.notes.value.map { it.id })
    }

    @Test
    fun `refresh forgets shown note ids`() {
        val dedup = NostrEventDeduplicator.getInstance()
        manager.setGeohash(geohash)
        deliver(note)
        dedup.isDuplicate("unrelated-id")

        manager.refresh()

        assertFalse(dedup.contains(note.id))
        assertTrue(dedup.contains("unrelated-id"))
        deliver(note)
        assertEquals(listOf(note.id), manager.notes.value.map { it.id })
    }

    @Test
    fun `no relay answer keeps loading and is not reported as empty`() = runTest(dispatcher) {
        manager.setGeohash(geohash)

        advanceTimeBy(LocationNotesManager.INITIAL_LOAD_FALLBACK_MS - 1)
        runCurrent()
        assertFalse(manager.initialLoadComplete.value)
        assertFalse(manager.relaysSlow.value)

        advanceTimeBy(1)
        runCurrent()
        assertTrue(manager.relaysSlow.value)
        assertEquals(LocationNotesManager.State.LOADING, manager.state.value)
        assertFalse(manager.initialLoadComplete.value)
    }

    @Test
    fun `first eose completes the load with no notes`() = runTest(dispatcher) {
        manager.setGeohash(geohash)

        endOfStoredEvents.first()()

        assertTrue(manager.initialLoadComplete.value)
        assertEquals(LocationNotesManager.State.READY, manager.state.value)
        assertTrue(manager.notes.value.isEmpty())
        assertFalse(manager.relaysSlow.value)
        advanceTimeBy(2 * LocationNotesManager.INITIAL_LOAD_FALLBACK_MS)
        runCurrent()
        assertFalse(manager.relaysSlow.value)
    }

    @Test
    fun `an event before any eose completes the load and later events still append`() = runTest(dispatcher) {
        manager.setGeohash(geohash)

        deliver(note)
        assertTrue(manager.initialLoadComplete.value)
        assertEquals(LocationNotesManager.State.READY, manager.state.value)

        endOfStoredEvents.last()()
        val later = syntheticNote("later")
        deliver(later)

        assertEquals(setOf(note.id, later.id), manager.notes.value.map { it.id }.toSet())
        assertEquals(LocationNotesManager.State.READY, manager.state.value)
    }

    @Test
    fun `notes arriving after the fallback clear relaysSlow`() = runTest(dispatcher) {
        manager.setGeohash(geohash)
        advanceTimeBy(LocationNotesManager.INITIAL_LOAD_FALLBACK_MS)
        runCurrent()
        assertTrue(manager.relaysSlow.value)

        deliver(note)

        assertFalse(manager.relaysSlow.value)
        assertEquals(listOf(note.id), manager.notes.value.map { it.id })
    }

    @Test
    fun `eose for a previous geohash is ignored`() = runTest(dispatcher) {
        manager.setGeohash(geohash)
        val stale = endOfStoredEvents.first()

        manager.setGeohash("s0000001")
        stale()

        assertFalse(manager.initialLoadComplete.value)
        assertEquals(LocationNotesManager.State.LOADING, manager.state.value)
    }

    @Test
    fun `stop cancels the fallback`() = runTest(dispatcher) {
        manager.setGeohash(geohash)

        manager.stop()
        advanceTimeBy(2 * LocationNotesManager.INITIAL_LOAD_FALLBACK_MS)
        runCurrent()

        assertFalse(manager.relaysSlow.value)
    }

    @Test
    fun `a note dropped after a cell change can be delivered when returning`() {
        manager.setGeohash(geohash)
        val previousCellHandler = handlers.last()
        // The relay manager marks the note seen, then queues the handler on Main
        assertFalse(NostrEventDeduplicator.getInstance().isDuplicate(note.id))
        manager.setGeohash("s0000zzz")
        previousCellHandler(note)

        manager.setGeohash(geohash)
        deliver(note)

        assertEquals(listOf(note.id), manager.notes.value.map { it.id })
    }

    @Test
    fun `a note dropped after stop can be delivered on the next open`() {
        manager.setGeohash(geohash)
        val handler = handlers.last()
        assertFalse(NostrEventDeduplicator.getInstance().isDuplicate(note.id))
        manager.stop()
        handler(note)

        manager.setGeohash(geohash)
        deliver(note)

        assertEquals(listOf(note.id), manager.notes.value.map { it.id })
    }

    @Test
    fun `eose from before a refresh does not complete the new load`() = runTest(dispatcher) {
        manager.setGeohash(geohash)
        val beforeRefresh = endOfStoredEvents.first()

        manager.refresh()
        beforeRefresh()

        assertFalse(manager.initialLoadComplete.value)
        assertEquals(LocationNotesManager.State.LOADING, manager.state.value)
        endOfStoredEvents.last()()
        assertTrue(manager.initialLoadComplete.value)
    }
}
