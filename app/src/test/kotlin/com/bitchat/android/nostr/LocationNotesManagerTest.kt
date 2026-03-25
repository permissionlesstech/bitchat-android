package com.bitchat.android.nostr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Unit tests for [LocationNotesManager] covering:
 *  - Incoming kind:1 (text note) events — adding, deduplication, geohash filtering
 *  - Incoming kind:5 (NIP-09 deletion) events — signature-verified, pubkey-gated removal
 *  - [LocationNotesManager.deleteNote] — optimistic local removal + relay broadcast
 *
 * The manager's private [handleEvent] method is exercised through the event-handler
 * callbacks captured from the [subscribe] lambda injected in [initialize].
 *
 * Two subscriptions are created:
 *  - kind:1 handler stored under "location-notes-<geohash>"
 *  - kind:5 deletion handler stored under "location-deletions", registered
 *    synchronously the first time a kind:1 note arrives (via maybeResubscribeDeletions)
 *
 * No Robolectric is required because [NostrCrypto] / [NostrIdentity] depend only on
 * BouncyCastle (pure JVM), and [android.util.Log] is stubbed by the project-level
 * test mock at src/test/kotlin/android/util/Log.kt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationNotesManagerTest {

    private lateinit var manager: LocationNotesManager

    /**
     * All event handlers registered via subscribe(), keyed by subscription ID.
     * The kind:1 text-note handler is stored under "location-notes-<geohash>".
     * The kind:5 deletion handler is stored under "location-deletions".
     */
    private val capturedHandlers = mutableMapOf<String, (NostrEvent) -> Unit>()

    /** Convenience accessor for the kind:1 note subscription handler. */
    private val capturedEventHandler: ((NostrEvent) -> Unit)?
        get() = capturedHandlers["location-notes-$testGeohash"]

    /** Convenience accessor for the kind:5 deletion subscription handler. */
    private val capturedDeletionHandler: ((NostrEvent) -> Unit)?
        get() = capturedHandlers["location-deletions"]

    /** Mutable hook so individual tests can install their own send-event spy. */
    @Volatile
    private var sendEventCallback: (NostrEvent, List<String>?) -> Unit = { _, _ -> }

    /** Two stable secp256k1 identities derived from fixed seeds (no Android context needed). */
    private val authorIdentity = NostrIdentity.fromSeed("location-notes-test-author-seed")
    private val attackerIdentity = NostrIdentity.fromSeed("location-notes-test-attacker-seed")

    /** A valid 8-character base32 geohash (building-level precision). */
    private val testGeohash = "u4pruydq"

    // ─── Test lifecycle ───────────────────────────────────────────────────────

    @Before
    fun setup() {
        // Dispatchers.Unconfined has isDispatchNeeded()=false — continuations that resume
        // after withContext(IO) run inline on the IO thread without being posted back to a
        // TestCoroutineScheduler, so deleteNote's latch-based sync works without pumping.
        // No virtual-clock control is needed: maybeResubscribeDeletions() is synchronous.
        Dispatchers.setMain(Dispatchers.Unconfined)

        // Create a brand-new manager instance via reflection, bypassing the singleton.
        // This guarantees a fresh, non-cancelled CoroutineScope for every test,
        // regardless of what any previous test (or its @After) did to the singleton.
        manager = createFreshInstance()

        manager.initialize(
            relayManager = { error("relayManager should not be called in these tests") },
            subscribe = { _, subId, handler ->
                capturedHandlers[subId] = handler
                subId
            },
            unsubscribe = { subId -> capturedHandlers.remove(subId) },
            sendEvent = { event, relays -> sendEventCallback(event, relays) },
            deriveIdentity = { _ -> authorIdentity },
        )

        // Triggers subscribeAll() synchronously, which populates capturedHandlers.
        manager.setGeohash(testGeohash)
    }

    @After
    fun teardown() {
        manager.cleanup()
        capturedHandlers.clear()
        Dispatchers.resetMain()
    }

    // ─── handleEvent: kind:1 (text notes) ────────────────────────────────────

    @Test
    fun `kind 1 event adds note to the list`() {
        capturedEventHandler!!.invoke(
            makeTextNoteEvent(id = "note-001", pubkey = authorIdentity.publicKeyHex, content = "Hello!")
        )

        assertEquals(1, manager.notes.value.size)
        assertEquals("note-001", manager.notes.value[0].id)
        assertEquals("Hello!", manager.notes.value[0].content)
    }

    @Test
    fun `kind 1 event with same id is deduplicated`() {
        val event = makeTextNoteEvent(id = "dup-note", pubkey = authorIdentity.publicKeyHex)
        capturedEventHandler!!.invoke(event)
        capturedEventHandler!!.invoke(event) // second delivery of the same event

        assertEquals("Duplicate event must not be stored twice", 1, manager.notes.value.size)
    }

    @Test
    fun `kind 1 event without geohash tag is ignored`() {
        val eventWithoutGtag = NostrEvent(
            id = "no-gtag",
            pubkey = authorIdentity.publicKeyHex,
            createdAt = 1_700_000_000,
            kind = NostrKind.TEXT_NOTE,
            tags = emptyList(), // intentionally no "g" tag
            content = "no location",
        )

        capturedEventHandler!!.invoke(eventWithoutGtag)

        assertEquals("Event without geohash tag must be ignored", 0, manager.notes.value.size)
    }

    @Test
    fun `kind 1 event with different geohash is ignored`() {
        val eventForOtherCell = makeTextNoteEvent(
            id = "other-cell",
            pubkey = authorIdentity.publicKeyHex,
            geohash = "s000000a", // different geohash, not subscribed
        )

        capturedEventHandler!!.invoke(eventForOtherCell)

        assertEquals("Event for a non-subscribed geohash must be ignored", 0, manager.notes.value.size)
    }

    @Test
    fun `kind 1 event stores nickname from n-tag`() {
        val eventWithNick = NostrEvent(
            id = "nick-note",
            pubkey = authorIdentity.publicKeyHex,
            createdAt = 1_700_000_000,
            kind = NostrKind.TEXT_NOTE,
            tags = listOf(listOf("g", testGeohash), listOf("n", "Alice")),
            content = "Hi from Alice",
        )

        capturedEventHandler!!.invoke(eventWithNick)

        assertEquals("Alice", manager.notes.value[0].nickname)
    }

    // ─── handleEvent: kind:5 (NIP-09 deletion) ───────────────────────────────
    //
    // maybeResubscribeDeletions() is synchronous — capturedDeletionHandler is
    // populated immediately when the first kind:1 note arrives, no time-advancement
    // needed. Deletion events must be properly signed; handleEvent() calls
    // isValidSignature() before honouring any kind:5 request.

    @Test
    fun `kind 5 removes the matching note authored by the same pubkey`() {
        capturedEventHandler!!.invoke(
            makeTextNoteEvent(id = "del-target", pubkey = authorIdentity.publicKeyHex, content = "To be deleted")
        )
        assertEquals(1, manager.notes.value.size)

        capturedDeletionHandler!!.invoke(
            makeDeletionEvent(targetId = "del-target", identity = authorIdentity)
        )

        assertEquals("Note must be removed by kind:5 from the same author", 0, manager.notes.value.size)
    }

    @Test
    fun `kind 5 does NOT remove a note when pubkey does not match`() {
        capturedEventHandler!!.invoke(
            makeTextNoteEvent(id = "protected", pubkey = authorIdentity.publicKeyHex, content = "Protected")
        )
        assertEquals(1, manager.notes.value.size)

        // Deletion request from a *different* identity — must be rejected
        capturedDeletionHandler!!.invoke(
            makeDeletionEvent(targetId = "protected", identity = attackerIdentity)
        )

        assertEquals("Note must survive a deletion attempt from a different pubkey", 1, manager.notes.value.size)
    }

    @Test
    fun `kind 5 removes only the referenced note and leaves others intact`() {
        capturedEventHandler!!.invoke(
            makeTextNoteEvent(id = "note-A", pubkey = authorIdentity.publicKeyHex, content = "Note A")
        )
        // note-A arrival registers capturedDeletionHandler synchronously (throttle window opens).
        // note-B arrival is throttled (< 1000 ms), but handleEvent() still processes all e-tags
        // regardless of which IDs were in the subscription filter at registration time.
        capturedEventHandler!!.invoke(
            makeTextNoteEvent(id = "note-B", pubkey = authorIdentity.publicKeyHex, content = "Note B")
        )
        assertEquals(2, manager.notes.value.size)

        capturedDeletionHandler!!.invoke(
            makeDeletionEvent(targetId = "note-A", identity = authorIdentity)
        )

        assertEquals("Only the referenced note should be removed", 1, manager.notes.value.size)
        assertEquals("note-B", manager.notes.value[0].id)
    }

    @Test
    fun `kind 5 with no e-tags does nothing`() {
        capturedEventHandler!!.invoke(
            makeTextNoteEvent(id = "safe-note", pubkey = authorIdentity.publicKeyHex, content = "Safe")
        )
        assertEquals(1, manager.notes.value.size)

        // A properly signed kind:5 with no e-tags — passes the signature guard but
        // targetIds will be empty so no note should be removed.
        val malformed = NostrEvent(
            pubkey = authorIdentity.publicKeyHex,
            createdAt = (System.currentTimeMillis() / 1000).toInt(),
            kind = NostrKind.DELETION,
            tags = emptyList(),
            content = "",
        ).sign(authorIdentity.privateKeyHex)
        capturedDeletionHandler!!.invoke(malformed)

        assertEquals("Malformed kind:5 with no e-tags must not remove any note", 1, manager.notes.value.size)
    }

    @Test
    fun `kind 5 can delete multiple notes in one event via multiple e-tags`() {
        capturedEventHandler!!.invoke(makeTextNoteEvent(id = "batch-A", pubkey = authorIdentity.publicKeyHex, content = "A"))
        capturedEventHandler!!.invoke(makeTextNoteEvent(id = "batch-B", pubkey = authorIdentity.publicKeyHex, content = "B"))
        capturedEventHandler!!.invoke(makeTextNoteEvent(id = "keep-C",  pubkey = authorIdentity.publicKeyHex, content = "C"))
        assertEquals(3, manager.notes.value.size)

        // A single kind:5 event referencing two note IDs simultaneously.
        val batchDeletion = NostrEvent(
            pubkey = authorIdentity.publicKeyHex,
            createdAt = (System.currentTimeMillis() / 1000).toInt(),
            kind = NostrKind.DELETION,
            tags = listOf(listOf("e", "batch-A"), listOf("e", "batch-B")),
            content = "",
        ).sign(authorIdentity.privateKeyHex)
        capturedDeletionHandler!!.invoke(batchDeletion)

        assertEquals("Both referenced notes must be removed", 1, manager.notes.value.size)
        assertEquals("keep-C", manager.notes.value[0].id)
    }

    // ─── deleteNote (optimistic local removal + relay broadcast) ─────────────

    @Test
    fun `deleteNote removes note optimistically before relay broadcast`() {
        capturedEventHandler!!.invoke(
            makeTextNoteEvent(id = "rm-note", pubkey = authorIdentity.publicKeyHex, content = "Will be deleted")
        )
        assertEquals(1, manager.notes.value.size)

        // sendEvent is called AFTER the optimistic removal — use it as a sync point.
        val latch = CountDownLatch(1)
        sendEventCallback = { _, _ -> latch.countDown() }

        manager.deleteNote("rm-note")

        assertTrue("deleteNote coroutine must complete within 3 s", latch.await(3, TimeUnit.SECONDS))
        assertEquals("Note must be removed from the list", 0, manager.notes.value.size)
    }

    @Test
    fun `deleteNote broadcasts a kind 5 event with the correct e-tag`() {
        capturedEventHandler!!.invoke(
            makeTextNoteEvent(id = "broadcast-me", pubkey = authorIdentity.publicKeyHex, content = "Broadcast target")
        )

        val captured = mutableListOf<NostrEvent>()
        val latch = CountDownLatch(1)
        sendEventCallback = { event, _ -> captured.add(event); latch.countDown() }

        manager.deleteNote("broadcast-me")

        assertTrue("Deletion event must be sent within 3 s", latch.await(3, TimeUnit.SECONDS))
        assertEquals("Exactly one event must be broadcast", 1, captured.size)
        assertEquals("Broadcast event must be kind 5", NostrKind.DELETION, captured[0].kind)

        val eTag = captured[0].tags.firstOrNull { it.size >= 2 && it[0] == "e" }
        assertNotNull("Broadcast kind:5 must have an e-tag", eTag)
        assertEquals("e-tag must reference the deleted note id", "broadcast-me", eTag!![1])
    }

    @Test
    fun `deleteNote does nothing when geohash is not set`() {
        // Create a separate bare instance with no geohash configured.
        val bare = createFreshInstance()
        bare.initialize(
            relayManager = { error("not needed") },
            subscribe = { _, subId, _ -> subId },
            unsubscribe = { },
            sendEvent = { _, _ -> error("sendEvent must not be called") },
            deriveIdentity = { _ -> authorIdentity },
        )
        // setGeohash intentionally NOT called — deleteNote should silently no-op.
        bare.deleteNote("irrelevant-id")
        bare.cleanup()
    }

    @Test
    fun `deleteNote does not delete or broadcast when note is not owned by local identity`() {
        capturedEventHandler!!.invoke(
            makeTextNoteEvent(id = "foreign-note", pubkey = attackerIdentity.publicKeyHex, content = "Not mine")
        )
        assertEquals(1, manager.notes.value.size)

        var sendCalled = false
        sendEventCallback = { _, _ -> sendCalled = true }

        manager.deleteNote("foreign-note")

        assertEquals("Foreign note must remain", 1, manager.notes.value.size)
        assertEquals("foreign-note", manager.notes.value[0].id)
        assertTrue("Deletion event must not be broadcast for non-owned note", !sendCalled)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Build a minimal kind:1 text-note event for a given geohash cell.
     * Left unsigned — [LocationNotesManager.handleEvent] does not verify signatures on kind:1.
     */
    private fun makeTextNoteEvent(
        id: String,
        pubkey: String,
        geohash: String = testGeohash,
        content: String = "test note content",
    ) = NostrEvent(
        id = id,
        pubkey = pubkey,
        createdAt = 1_700_000_000,
        kind = NostrKind.TEXT_NOTE,
        tags = listOf(listOf("g", geohash)),
        content = content,
    )

    /**
     * Build a properly signed kind:5 deletion event targeting [targetId], authored by [identity].
     *
     * The event is signed via BIP-340 Schnorr because [LocationNotesManager.handleEvent]
     * calls [NostrEvent.isValidSignature] before honouring any kind:5 request.
     */
    private fun makeDeletionEvent(
        targetId: String,
        identity: NostrIdentity,
    ) = NostrEvent(
        pubkey = identity.publicKeyHex,
        createdAt = (System.currentTimeMillis() / 1000).toInt(),
        kind = NostrKind.DELETION,
        tags = listOf(listOf("e", targetId)),
        content = "",
    ).sign(identity.privateKeyHex)

    /**
     * Create a fresh [LocationNotesManager] instance by invoking its private constructor
     * via reflection.  This bypasses the singleton so every test gets an independent
     * object with a non-cancelled [CoroutineScope].
     */
    private fun createFreshInstance(): LocationNotesManager {
        val ctor = LocationNotesManager::class.java.getDeclaredConstructor()
        ctor.isAccessible = true
        return ctor.newInstance() as LocationNotesManager
    }
}
