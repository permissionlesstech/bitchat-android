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
    fun `new scroll range from appended message keeps follow intent`() {
        val updated = updatedChatScrollIntent(
            current = ChatScrollIntentState(),
            snapshot = ChatScrollSnapshot(
                canScrollForward = true,
                isScrollInProgress = false,
                position = 100
            ),
            previousPosition = 100
        )

        assertEquals(ChatScrollIntentState(), updated)
    }

    @Test
    fun `user scroll away disables follow until list reaches newest again`() {
        val browsingHistory = updatedChatScrollIntent(
            current = ChatScrollIntentState(),
            snapshot = ChatScrollSnapshot(
                canScrollForward = true,
                isScrollInProgress = true,
                position = 60
            ),
            previousPosition = 100
        )
        assertFalse(browsingHistory.followsNewest)
        assertFalse(browsingHistory.controlsVisible)

        val dockedAgain = updatedChatScrollIntent(
            current = browsingHistory,
            snapshot = ChatScrollSnapshot(
                canScrollForward = false,
                isScrollInProgress = false,
                position = 200
            ),
            previousPosition = 60
        )
        assertEquals(ChatScrollIntentState(), dockedAgain)
    }

    @Test
    fun `slow scroll away accumulates intent across sub-threshold updates`() {
        var state = ChatScrollIntentState()
        var previousPosition = 100

        listOf(94, 88, 82, 76).forEach { position ->
            state = updatedChatScrollIntent(
                current = state,
                snapshot = ChatScrollSnapshot(
                    canScrollForward = true,
                    isScrollInProgress = true,
                    position = position
                ),
                previousPosition = previousPosition
            )
            previousPosition = position
            state = updatedChatScrollIntent(
                current = state,
                snapshot = ChatScrollSnapshot(
                    canScrollForward = true,
                    isScrollInProgress = false,
                    position = position
                ),
                previousPosition = previousPosition
            )
        }

        assertFalse(state.followsNewest)
        assertFalse(state.controlsVisible)
        assertEquals(0, state.accumulatedDeltaPx)
    }

    @Test
    fun `slow scroll toward newest reveals controls without restoring follow early`() {
        var state = ChatScrollIntentState(followsNewest = false, controlsVisible = false)
        var previousPosition = 60

        listOf(66, 72, 78, 84).forEach { position ->
            state = updatedChatScrollIntent(
                current = state,
                snapshot = ChatScrollSnapshot(
                    canScrollForward = true,
                    isScrollInProgress = true,
                    position = position
                ),
                previousPosition = previousPosition
            )
            previousPosition = position
        }

        assertFalse(state.followsNewest)
        assertEquals(true, state.controlsVisible)
        assertEquals(0, state.accumulatedDeltaPx)
    }

    @Test
    fun `newest scroll waits until appended message is measured`() = runTest {
        val measuredLayouts = MutableStateFlow(MeasuredChatLayout(3, null))
        var scrollCount = 0

        val scrollJob = launch(start = CoroutineStart.UNDISPATCHED) {
            scrollToNewestAfterItemsMeasured(
                expectedItemCount = 4,
                expectedSingleMessageKey = null,
                measuredLayouts = measuredLayouts
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
            measuredLayouts = measuredLayouts
        ) {
            scrollCount += 1
        }

        assertEquals(1, scrollCount)
    }

    @Test
    fun `first message waits past stale empty placeholder layout`() = runTest {
        val messageKey = "first-message"
        val measuredLayouts = MutableStateFlow(
            MeasuredChatLayout(itemCount = 1, singleVisibleItemKey = "empty-placeholder")
        )
        var scrollCount = 0

        val scrollJob = launch(start = CoroutineStart.UNDISPATCHED) {
            scrollToNewestAfterItemsMeasured(
                expectedItemCount = 1,
                expectedSingleMessageKey = messageKey,
                measuredLayouts = measuredLayouts
            ) {
                scrollCount += 1
            }
        }

        assertFalse(scrollJob.isCompleted)
        assertEquals(0, scrollCount)

        measuredLayouts.value = MeasuredChatLayout(
            itemCount = 1,
            singleVisibleItemKey = messageKey
        )
        scrollJob.join()

        assertEquals(1, scrollCount)
    }
}
