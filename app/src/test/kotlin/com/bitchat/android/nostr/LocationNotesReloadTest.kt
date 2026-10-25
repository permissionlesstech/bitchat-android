package com.bitchat.android.nostr

import com.bitchat.android.geohash.LiveLocationPrivacyGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
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
    private val geohash = "s0000000"
    private lateinit var note: NostrEvent

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        LiveLocationPrivacyGate.update(true)
        NostrEventDeduplicator.getInstance().clear()
        manager.initialize(
            relayManager = { error("unused") },
            subscribe = { _, id, handler ->
                handlers += handler
                id
            },
            unsubscribe = {},
            sendEvent = { _, _, _ -> },
            deriveIdentity = { error("unused") },
        )
        note = runBlocking {
            NostrProtocol.createGeohashTextNote(
                content = "synthetic",
                geohash = geohash,
                senderIdentity = NostrIdentity.generate(),
                nickname = null,
            )
        }
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
}
