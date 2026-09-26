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
