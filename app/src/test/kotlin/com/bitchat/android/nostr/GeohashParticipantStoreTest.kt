package com.bitchat.android.nostr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeohashParticipantStoreTest {
    private var now = 1_000_000L
    private val store = GeohashParticipantStore { now }

    @Test
    fun `out of order heartbeats cannot age out an active chatter`() {
        store.updateParticipant("s00000", "a1", now)
        store.updateParticipant("s00000", "A1", now - 600_000)
        store.pruneExpired()

        assertEquals(mapOf("a1" to now), store.snapshots.value.participants["s00000"])
    }

    @Test
    fun `future timestamps cannot keep a participant online past five minutes`() {
        store.updateParticipant("s00000", "a1", now + 600_000)
        now += 300_000
        store.pruneExpired()
        assertEquals(1, store.snapshots.value.participants["s00000"]?.size)

        now += 1
        store.pruneExpired()
        assertTrue(store.snapshots.value.participants["s00000"].orEmpty().isEmpty())
    }

    @Test
    fun `chat authors stay participants for the hour, as on the globe`() {
        store.recordEvent("s00000", "a1", NostrKind.EPHEMERAL_EVENT, "e1", now, "hello")
        store.recordEvent("s00000", "b2", NostrKind.GEOHASH_PRESENCE, "e2", now, "")

        now += 40 * 60_000L
        store.pruneExpired()
        assertEquals(setOf("a1"), store.snapshots.value.participants["s00000"]?.keys)

        now += 21 * 60_000L
        store.pruneExpired()
        assertTrue(store.snapshots.value.participants["s00000"].orEmpty().isEmpty())
    }

    @Test
    fun `clearing shared presence removes identities names and teleport markers`() {
        store.updateParticipant("s00000", "a1", now)
        store.cacheNickname("a1", "fixture-speaker")
        store.markTeleported("a1")

        store.clear()

        assertEquals(GeohashParticipantStore.Snapshot(), store.snapshots.value)
    }

    @Test
    fun `concurrent chat and heartbeat updates retain every participant`() = runBlocking {
        coroutineScope {
            repeat(100) { index ->
                launch(Dispatchers.Default) {
                    store.updateParticipant("s00000", "fixture-$index", now)
                    store.cacheNickname("fixture-$index", "speaker-$index")
                }
            }
        }

        assertEquals(100, store.snapshots.value.participants["s00000"]?.size)
        assertEquals(100, store.snapshots.value.nicknames.size)
    }
}
