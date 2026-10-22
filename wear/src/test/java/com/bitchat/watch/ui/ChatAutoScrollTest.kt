package com.bitchat.watch.ui

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ChatAutoScrollTest {

    @Test
    fun `append waits for active gesture and rechecks history intent`() = runTest {
        val scrolling = MutableStateFlow(true)
        var followsNewest = true
        var scrollCount = 0
        val job =
            launch(start = CoroutineStart.UNDISPATCHED) {
                followNewestWhenIdle(scrolling, { followsNewest }) { scrollCount++ }
            }
        assertFalse(job.isCompleted)
        assertEquals(0, scrollCount)
        followsNewest = false
        scrolling.value = false
        job.join()
        assertEquals(0, scrollCount)
    }

    @Test
    fun `append follows after gesture settles when user remains at newest`() = runTest {
        val scrolling = MutableStateFlow(true)
        var scrollCount = 0
        val job =
            launch(start = CoroutineStart.UNDISPATCHED) {
                followNewestWhenIdle(scrolling, { true }) { scrollCount++ }
            }
        assertEquals(0, scrollCount)
        scrolling.value = false
        job.join()
        assertEquals(1, scrollCount)
    }

    private fun move(
        state: ChatScrollIntentState,
        dp: Float,
        user: Boolean = true,
        newest: Boolean = false,
    ) = updatedChatScrollIntent(state, dp, user, newest)

    @Test
    fun `append and programmatic movement never change intent`() {
        val docked = ChatScrollIntentState()
        assertEquals(docked, move(docked, 1000f, user = false))
        val history = ChatScrollIntentState(false, false)
        assertEquals(history, move(history, 1000f, user = false))
    }

    @Test
    fun `first consumed movement away suspends following before controls hide`() {
        val state = move(ChatScrollIntentState(), -1f)
        assertFalse(state.followsNewest)
        assertEquals(true, state.controlsVisible)
        assertFalse(move(state, -11f).controlsVisible)
    }

    @Test
    fun `deliberate reversal reveals controls but does not resume following`() {
        val history = move(ChatScrollIntentState(), -12f)
        val almost = move(history, 23f)
        assertFalse(almost.controlsVisible)
        val revealed = move(almost, 1f)
        assertEquals(true, revealed.controlsVisible)
        assertFalse(revealed.followsNewest)
        assertEquals(ChatScrollIntentState(), move(revealed, 1f, newest = true))
    }

    @Test
    fun `jitter does not accumulate into repeated toggles`() {
        var state = move(ChatScrollIntentState(), -12f)
        repeat(100) {
            state = move(state, 3f)
            state = move(state, -3f)
        }
        assertFalse(state.controlsVisible)
        assertEquals(0f, state.reversalDp)
    }

    @Test
    fun `pauses and discrete crown ticks retain net movement`() {
        var state = ChatScrollIntentState()
        repeat(4) {
            state = move(state, -3f)
            state = move(state, 0f, user = false)
        }
        assertFalse(state.controlsVisible)
        repeat(8) {
            state = move(state, 3f)
            state = move(state, 0f, user = false)
        }
        assertEquals(true, state.controlsVisible)
        assertFalse(state.followsNewest)
    }

    @Test
    fun `fling preserves controls until newest is reached`() {
        val history = move(ChatScrollIntentState(), -12f)
        assertEquals(history, move(history, 1000f, user = false))
        assertEquals(history, move(history, -1000f, user = false))
        assertEquals(ChatScrollIntentState(), move(history, 1f, user = false, newest = true))
    }

    @Test
    fun `consumed distance is independent of item boundaries and event chunking`() {
        val initial = ChatScrollIntentState()
        val oneEvent = move(initial, -12f)
        val acrossRows =
            listOf(-2f, -3f, -1f, -6f).fold(initial) { state, delta ->
                move(state, delta)
            }
        assertEquals(oneEvent, acrossRows)
    }

    @Test
    fun `pixel distances normalize to the same dp thresholds`() {
        for (density in listOf(1f, 1.6875f, 2f, 3f)) {
            val history = move(ChatScrollIntentState(), (-12f * density) / density)
            assertFalse(history.controlsVisible)
            assertEquals(true, move(history, (24f * density) / density).controlsVisible)
        }
    }

    @Test
    fun `invalid and unconsumed input is ignored`() {
        val initial = ChatScrollIntentState()
        assertEquals(initial, move(initial, Float.NaN))
        assertEquals(initial, move(initial, Float.POSITIVE_INFINITY))
        assertEquals(initial, move(initial, 0f))
    }

    @Test
    fun `newest scroll waits until appended message is measured`() = runTest {
        val measuredLayouts = MutableStateFlow(MeasuredChatLayout(3, null))
        var scrollCount = 0

        val scrollJob =
            launch(start = CoroutineStart.UNDISPATCHED) {
                scrollToNewestAfterItemsMeasured(
                    expectedItemCount = 4,
                    expectedSingleMessageKey = null,
                    measuredLayouts = measuredLayouts,
                ) {
                    scrollCount += 1
                }
            }

        assertFalse(scrollJob.isCompleted)
        assertEquals(0, scrollCount)

        measuredLayouts.value = MeasuredChatLayout(4, null)
        scrollJob.join()

        assertEquals(1, scrollCount)
    }

    @Test
    fun `newest scroll runs immediately when messages are already measured`() = runTest {
        val measuredLayouts = MutableStateFlow(MeasuredChatLayout(4, null))
        var scrollCount = 0

        scrollToNewestAfterItemsMeasured(
            expectedItemCount = 4,
            expectedSingleMessageKey = null,
            measuredLayouts = measuredLayouts,
        ) {
            scrollCount += 1
        }

        assertEquals(1, scrollCount)
    }

    @Test
    fun `first message waits past stale empty placeholder layout`() = runTest {
        val messageKey = "first-message"
        val measuredLayouts =
            MutableStateFlow(
                MeasuredChatLayout(itemCount = 1, singleVisibleItemKey = "empty-placeholder")
            )
        var scrollCount = 0

        val scrollJob =
            launch(start = CoroutineStart.UNDISPATCHED) {
                scrollToNewestAfterItemsMeasured(
                    expectedItemCount = 1,
                    expectedSingleMessageKey = messageKey,
                    measuredLayouts = measuredLayouts,
                ) {
                    scrollCount += 1
                }
            }

        assertFalse(scrollJob.isCompleted)
        assertEquals(0, scrollCount)

        measuredLayouts.value =
            MeasuredChatLayout(
                itemCount = 1,
                singleVisibleItemKey = messageKey,
            )
        scrollJob.join()

        assertEquals(1, scrollCount)
    }
}
