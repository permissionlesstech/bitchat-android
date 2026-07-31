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
        val measuredItemCount = MutableStateFlow(3)
        var scrollCount = 0

        val scrollJob = launch(start = CoroutineStart.UNDISPATCHED) {
            scrollToNewestAfterItemsMeasured(
                expectedItemCount = 4,
                measuredItemCounts = measuredItemCount
            ) {
                scrollCount += 1
            }
        }

        assertFalse(scrollJob.isCompleted)
        assertEquals(0, scrollCount)

        measuredItemCount.value = 4
        scrollJob.join()

        assertEquals(1, scrollCount)
    }

    @Test
    fun `newest scroll runs immediately when messages are already measured`() = runTest {
        val measuredItemCount = MutableStateFlow(4)
        var scrollCount = 0

        scrollToNewestAfterItemsMeasured(
            expectedItemCount = 4,
            measuredItemCounts = measuredItemCount
        ) {
            scrollCount += 1
        }

        assertEquals(1, scrollCount)
    }
}
