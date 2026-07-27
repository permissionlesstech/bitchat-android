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
