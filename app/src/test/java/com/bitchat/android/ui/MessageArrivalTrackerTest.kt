package com.bitchat.android.ui

import com.bitchat.android.model.BitchatMessage
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arrival tracker decides which messages animate in.
 *
 * Getting it wrong is not subtle: animate too eagerly and every old message replays its entrance
 * whenever `LazyColumn` recycles it back into view, which looks broken during a scroll.
 */
class MessageArrivalTrackerTest {

    private var seq = 0

    private fun msg(id: String = "m${seq++}") = BitchatMessage(
        id = id,
        sender = "alice",
        content = "hello",
        timestamp = Date(0),
    )

    @Test
    fun `first load adopts everything silently`() {
        val tracker = MessageArrivalTracker()
        val initial = listOf(msg("a"), msg("b"), msg("c"))

        assertTrue(
            "a whole screenful animating on open reads as a glitch",
            tracker.arrivals(initial).isEmpty()
        )
    }

    @Test
    fun `a message appended after the first load animates`() {
        val tracker = MessageArrivalTracker()
        val messages = mutableListOf(msg("a"), msg("b"))
        tracker.arrivals(messages)

        messages += msg("c")
        assertEquals(setOf("c"), tracker.arrivals(messages))
    }

    @Test
    fun `an empty first load still lets the first real message animate`() {
        // A brand new conversation seeds with nothing, so its opening message is a genuine arrival.
        val tracker = MessageArrivalTracker()
        tracker.arrivals(emptyList())

        assertEquals(setOf("a"), tracker.arrivals(listOf(msg("a"))))
    }

    @Test
    fun `re-presenting the same list animates nothing`() {
        val tracker = MessageArrivalTracker()
        val messages = listOf(msg("a"), msg("b"))
        tracker.arrivals(messages)

        assertTrue(tracker.arrivals(messages).isEmpty())
        assertTrue(tracker.arrivals(messages).isEmpty())
    }

    @Test
    fun `an already-seen message never animates again`() {
        // Stands in for LazyColumn recycling an old row back into view mid-scroll.
        val tracker = MessageArrivalTracker()
        val messages = mutableListOf(msg("a"))
        tracker.arrivals(messages)
        messages += msg("b")
        tracker.arrivals(messages)

        assertTrue(tracker.arrivals(messages).isEmpty())
    }

    @Test
    fun `only the new messages in a batch animate`() {
        val tracker = MessageArrivalTracker()
        val messages = mutableListOf(msg("a"))
        tracker.arrivals(messages)

        messages += listOf(msg("b"), msg("c"))
        assertEquals(setOf("b", "c"), tracker.arrivals(messages))
    }

    @Test
    fun `a burst larger than the cap is adopted silently`() {
        // History sync: animating hundreds of rows would spend the whole frame budget on motion
        // nobody asked to see.
        val tracker = MessageArrivalTracker()
        val messages = mutableListOf(msg("seed"))
        tracker.arrivals(messages)

        repeat(MaxAnimatedArrivals + 1) { messages += msg("burst$it") }
        assertTrue(tracker.arrivals(messages).isEmpty())
    }

    @Test
    fun `a burst exactly at the cap still animates`() {
        val tracker = MessageArrivalTracker()
        val messages = mutableListOf(msg("seed"))
        tracker.arrivals(messages)

        repeat(MaxAnimatedArrivals) { messages += msg("b$it") }
        assertEquals(MaxAnimatedArrivals, tracker.arrivals(messages).size)
    }

    @Test
    fun `clearing the conversation prunes the known set`() {
        val tracker = MessageArrivalTracker()
        val messages = mutableListOf(msg("a"), msg("b"), msg("c"))
        tracker.arrivals(messages)

        tracker.arrivals(emptyList())
        assertTrue("stale ids would leak and grow without bound", tracker.known.isEmpty())
    }

    @Test
    fun `a message re-added after a clear animates again`() {
        val tracker = MessageArrivalTracker()
        tracker.arrivals(listOf(msg("a")))
        tracker.arrivals(emptyList())

        assertEquals(setOf("a"), tracker.arrivals(listOf(msg("a"))))
    }

    @Test
    fun `a wholesale replacement animates nothing`() {
        // Switching channels, or /clear followed by fresh content: the incoming list shares no ids
        // with the outgoing one, so it is a different conversation rather than a burst of arrivals.
        val tracker = MessageArrivalTracker()
        tracker.arrivals(listOf(msg("a"), msg("b")))

        val other = listOf(msg("x"), msg("y"))
        assertTrue(
            "a different conversation must not slide every message in",
            tracker.arrivals(other).isEmpty()
        )
    }

    @Test
    fun `a small replacement below the burst cap still animates nothing`() {
        // The burst cap alone would not catch this: two messages is well under it.
        val tracker = MessageArrivalTracker()
        tracker.arrivals(listOf(msg("a"), msg("b"), msg("c")))

        assertTrue(tracker.arrivals(listOf(msg("x"), msg("y"))).isEmpty())
    }

    @Test
    fun `a replacement that overlaps is treated as normal arrivals`() {
        // Still the same conversation if anything carries over, so genuine new messages animate.
        val tracker = MessageArrivalTracker()
        val kept = msg("a")
        tracker.arrivals(listOf(kept, msg("b")))

        assertEquals(setOf("c"), tracker.arrivals(listOf(kept, msg("c"))))
    }

    @Test
    fun `after a wholesale replacement, later arrivals animate normally`() {
        val tracker = MessageArrivalTracker()
        tracker.arrivals(listOf(msg("a")))

        val switched = mutableListOf(msg("x"))
        tracker.arrivals(switched)          // adopted silently

        switched += msg("y")
        assertEquals(setOf("y"), tracker.arrivals(switched))
    }

    @Test
    fun `the known set never outgrows the conversation`() {
        val tracker = MessageArrivalTracker()
        val messages = mutableListOf(msg("a"), msg("b"))
        tracker.arrivals(messages)

        // Simulate a channel switch: entirely different messages, same list size.
        val switched = listOf(msg("x"), msg("y"))
        tracker.arrivals(switched)

        assertEquals(setOf("x", "y"), tracker.known)
    }
}
