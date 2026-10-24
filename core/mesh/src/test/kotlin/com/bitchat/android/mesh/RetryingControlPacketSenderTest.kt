package com.bitchat.android.mesh

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RetryingControlPacketSenderTest {
    @Test
    fun `control packet is sent for the full redundant retry window`() = runTest {
        val attempts = mutableListOf<Int>()
        val sender = RetryingControlPacketSender(
            scope = this,
            maxAttempts = 3,
            retryDelayMs = 10,
            interSendDelayMs = 1
        )

        sender.enqueue(
            key = "peer:message",
            sendAttempt = { attempt ->
                attempts += attempt
                true
            }
        )
        advanceUntilIdle()

        assertEquals(listOf(1, 2, 3), attempts)
    }

    @Test
    fun `duplicate enqueue is coalesced while receipt retry is active`() = runTest {
        var firstRequestAttempts = 0
        var duplicateRequestAttempts = 0
        val sender = RetryingControlPacketSender(
            scope = this,
            maxAttempts = 3,
            retryDelayMs = 10,
            interSendDelayMs = 1
        )

        sender.enqueue(
            key = "peer:message",
            sendAttempt = {
                firstRequestAttempts += 1
                true
            }
        )
        sender.enqueue(
            key = "peer:message",
            sendAttempt = {
                duplicateRequestAttempts += 1
                true
            }
        )
        advanceUntilIdle()

        assertEquals(3, firstRequestAttempts)
        assertEquals(0, duplicateRequestAttempts)
    }

    @Test
    fun `transport writes for different receipts are serialized`() = runTest {
        var activeWrites = 0
        var maximumActiveWrites = 0
        val sender = RetryingControlPacketSender(
            scope = this,
            maxAttempts = 1,
            retryDelayMs = 0,
            interSendDelayMs = 0
        )

        fun enqueue(key: String) {
            sender.enqueue(
                key = key,
                sendAttempt = {
                    activeWrites += 1
                    maximumActiveWrites = maxOf(maximumActiveWrites, activeWrites)
                    delay(10)
                    activeWrites -= 1
                    true
                }
            )
        }

        enqueue("peer:first")
        enqueue("peer:second")
        advanceUntilIdle()

        assertEquals(1, maximumActiveWrites)
    }

    @Test
    fun `completion reports whether transport accepted any attempt`() = runTest {
        val completions = mutableListOf<Boolean>()
        val sender = RetryingControlPacketSender(
            scope = this,
            maxAttempts = 3,
            retryDelayMs = 1,
            interSendDelayMs = 0
        )

        sender.enqueue(
            key = "peer:rejected",
            sendAttempt = { false },
            onComplete = completions::add
        )
        sender.enqueue(
            key = "peer:eventually-accepted",
            sendAttempt = { attempt -> attempt == 2 },
            onComplete = completions::add
        )
        advanceUntilIdle()

        assertEquals(2, completions.size)
        assertEquals(setOf(false, true), completions.toSet())
    }
}
