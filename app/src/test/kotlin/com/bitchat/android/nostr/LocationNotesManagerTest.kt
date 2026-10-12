package com.bitchat.android.nostr

import com.bitchat.android.geohash.LiveLocationPrivacyGate
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

    /** Convenience accessor for the kind:1 note subscription handler.
     *  Subscription IDs are "location-notes-<UUID>"; every geohash cell's
     *  subscription shares the same handleEvent callback, so any entry works. */
    private val capturedEventHandler: ((NostrEvent) -> Unit)?
        get() = capturedHandlers.entries.firstOrNull { it.key.startsWith("location-notes-") }?.value

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

        // Arm the process-wide live-location consent gate (fail-closed by default);
        // without it setGeohash/handleEvent/deleteNote are all no-ops.
        LiveLocationPrivacyGate.update(true)

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
            sendEvent = { event, relays, _ -> sendEventCallback(event, relays) },
            deriveIdentity = { _ -> authorIdentity },
        )

        // Triggers subscribeAll() synchronously, which populates capturedHandlers.
        manager.setGeohash(testGeohash)
    }

    @After
    fun teardown() {
        manager.cleanup()
        capturedHandlers.clear()
        // Restore the gate to its fail-closed default so other test classes
        // sharing this JVM see the untouched state.
        LiveLocationPrivacyGate.update(false)
        Dispatchers.resetMain()
    }

    // ─── handleEvent: kind:1 (text notes) ────────────────────────────────────

    @Test
    fun `kind 1 event adds note to the list`() {
        val note = makeTextNoteEvent(identity = authorIdentity, content = "Hello!")
        capturedEventHandler!!.invoke(note)

        assertEquals(1, manager.notes.value.size)
        assertEquals(note.id, manager.notes.value[0].id)
        assertEquals("Hello!", manager.notes.value[0].content)
    }

    @Test
    fun `kind 1 event with same id is deduplicated`() {
        val event = makeTextNoteEvent(identity = authorIdentity)
        capturedEventHandler!!.invoke(event)
        capturedEventHandler!!.invoke(event) // second delivery of the same event

        assertEquals("Duplicate event must not be stored twice", 1, manager.notes.value.size)
    }

    @Test
    fun `kind 1 event without geohash tag is ignored`() {
        val eventWithoutGtag = makeTextNoteEvent(
            identity = authorIdentity,
            content = "no location",
            tags = emptyList(), // intentionally no "g" tag
        )

        capturedEventHandler!!.invoke(eventWithoutGtag)

        assertEquals("Event without geohash tag must be ignored", 0, manager.notes.value.size)
    }

    @Test
    fun `kind 1 event with different geohash is ignored`() {
        val eventForOtherCell = makeTextNoteEvent(
            identity = authorIdentity,
            tags = listOf(listOf("g", "s000000a")), // different geohash, not subscribed
        )

        capturedEventHandler!!.invoke(eventForOtherCell)

        assertEquals("Event for a non-subscribed geohash must be ignored", 0, manager.notes.value.size)
    }

    @Test
    fun `kind 1 event stores nickname from n-tag`() {
        val eventWithNick = makeTextNoteEvent(
            identity = authorIdentity,
            content = "Hi from Alice",
            tags = listOf(listOf("g", testGeohash), listOf("n", "Alice")),
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
        val target = makeTextNoteEvent(identity = authorIdentity, content = "To be deleted")
        capturedEventHandler!!.invoke(target)
        assertEquals(1, manager.notes.value.size)

        capturedDeletionHandler!!.invoke(
            makeDeletionEvent(targetId = target.id, identity = authorIdentity)
        )

        assertEquals("Note must be removed by kind:5 from the same author", 0, manager.notes.value.size)
    }

    @Test
    fun `kind 5 does NOT remove a note when pubkey does not match`() {
        val protectedNote = makeTextNoteEvent(identity = authorIdentity, content = "Protected")
        capturedEventHandler!!.invoke(protectedNote)
        assertEquals(1, manager.notes.value.size)

        // Deletion request from a *different* identity — must be rejected
        capturedDeletionHandler!!.invoke(
            makeDeletionEvent(targetId = protectedNote.id, identity = attackerIdentity)
        )

        assertEquals("Note must survive a deletion attempt from a different pubkey", 1, manager.notes.value.size)
    }

    @Test
    fun `kind 5 removes only the referenced note and leaves others intact`() {
        val noteA = makeTextNoteEvent(identity = authorIdentity, content = "Note A")
        val noteB = makeTextNoteEvent(identity = authorIdentity, content = "Note B")
        capturedEventHandler!!.invoke(noteA)
        // note-A arrival registers capturedDeletionHandler synchronously (throttle window opens).
        // note-B arrival is throttled (< 1000 ms), but handleEvent() still processes all e-tags
        // regardless of which IDs were in the subscription filter at registration time.
        capturedEventHandler!!.invoke(noteB)
        assertEquals(2, manager.notes.value.size)

        capturedDeletionHandler!!.invoke(
            makeDeletionEvent(targetId = noteA.id, identity = authorIdentity)
        )

        assertEquals("Only the referenced note should be removed", 1, manager.notes.value.size)
        assertEquals(noteB.id, manager.notes.value[0].id)
    }

    @Test
    fun `kind 5 with no e-tags does nothing`() {
        capturedEventHandler!!.invoke(
            makeTextNoteEvent(identity = authorIdentity, content = "Safe")
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
        val batchA = makeTextNoteEvent(identity = authorIdentity, content = "A")
        val batchB = makeTextNoteEvent(identity = authorIdentity, content = "B")
        val keepC = makeTextNoteEvent(identity = authorIdentity, content = "C")
        capturedEventHandler!!.invoke(batchA)
        capturedEventHandler!!.invoke(batchB)
        capturedEventHandler!!.invoke(keepC)
        assertEquals(3, manager.notes.value.size)

        // A single kind:5 event referencing two note IDs simultaneously.
        val batchDeletion = NostrEvent(
            pubkey = authorIdentity.publicKeyHex,
            createdAt = (System.currentTimeMillis() / 1000).toInt(),
            kind = NostrKind.DELETION,
            tags = listOf(listOf("e", batchA.id), listOf("e", batchB.id)),
            content = "",
        ).sign(authorIdentity.privateKeyHex)
        capturedDeletionHandler!!.invoke(batchDeletion)

        assertEquals("Both referenced notes must be removed", 1, manager.notes.value.size)
        assertEquals(keepC.id, manager.notes.value[0].id)
    }

    // ─── deleteNote (optimistic local removal + relay broadcast) ─────────────

    @Test
    fun `deleteNote removes note optimistically before relay broadcast`() {
        val note = makeTextNoteEvent(identity = authorIdentity, content = "Will be deleted")
        capturedEventHandler!!.invoke(note)
        assertEquals(1, manager.notes.value.size)

        // sendEvent is called AFTER the optimistic removal — use it as a sync point.
        val latch = CountDownLatch(1)
        sendEventCallback = { _, _ -> latch.countDown() }

        manager.deleteNote(note.id)

        assertTrue("deleteNote coroutine must complete within 3 s", latch.await(3, TimeUnit.SECONDS))
        assertEquals("Note must be removed from the list", 0, manager.notes.value.size)
    }

    @Test
    fun `deleteNote broadcasts a kind 5 event with the correct e-tag`() {
        val note = makeTextNoteEvent(identity = authorIdentity, content = "Broadcast target")
        capturedEventHandler!!.invoke(note)

        val captured = mutableListOf<NostrEvent>()
        val latch = CountDownLatch(1)
        sendEventCallback = { event, _ -> captured.add(event); latch.countDown() }

        manager.deleteNote(note.id)

        assertTrue("Deletion event must be sent within 3 s", latch.await(3, TimeUnit.SECONDS))
        assertEquals("Exactly one event must be broadcast", 1, captured.size)
        assertEquals("Broadcast event must be kind 5", NostrKind.DELETION, captured[0].kind)

        val eTag = captured[0].tags.firstOrNull { it.size >= 2 && it[0] == "e" }
        assertNotNull("Broadcast kind:5 must have an e-tag", eTag)
        assertEquals("e-tag must reference the deleted note id", note.id, eTag!![1])
    }

    @Test
    fun `deleteNote does nothing when geohash is not set`() {
        // Create a separate bare instance with no geohash configured.
        val bare = createFreshInstance()
        bare.initialize(
            relayManager = { error("not needed") },
            subscribe = { _, subId, _ -> subId },
            unsubscribe = { },
            sendEvent = { _, _, _ -> error("sendEvent must not be called") },
            deriveIdentity = { _ -> authorIdentity },
        )
        // setGeohash intentionally NOT called — deleteNote should silently no-op.
        bare.deleteNote("irrelevant-id")
        bare.cleanup()
    }

    @Test
    fun `deleteNote does not delete or broadcast when note is not owned by local identity`() {
        val foreignNote = makeTextNoteEvent(identity = attackerIdentity, content = "Not mine")
        capturedEventHandler!!.invoke(foreignNote)
        assertEquals(1, manager.notes.value.size)

        var sendCalled = false
        sendEventCallback = { _, _ -> sendCalled = true }

        manager.deleteNote(foreignNote.id)

        assertEquals("Foreign note must remain", 1, manager.notes.value.size)
        assertEquals(foreignNote.id, manager.notes.value[0].id)
        assertTrue("Deletion event must not be broadcast for non-owned note", !sendCalled)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Build a properly signed kind:1 text-note event authored by [identity].
     *
     * The event is signed via BIP-340 Schnorr because [LocationNotesManager.handleEvent]
     * calls [NostrEvent.isValidSignature] before accepting any kind:1 note. Signing also
     * derives the event id from the content, so tests reference notes via `event.id`
     * and each note in a test needs distinct [content] or [tags].
     */
    private fun makeTextNoteEvent(
        identity: NostrIdentity,
        content: String = "test note content",
        tags: List<List<String>> = listOf(listOf("g", testGeohash)),
    ) = NostrEvent(
        pubkey = identity.publicKeyHex,
        createdAt = 1_700_000_000,
        kind = NostrKind.TEXT_NOTE,
        tags = tags,
        content = content,
    ).sign(identity.privateKeyHex)

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
