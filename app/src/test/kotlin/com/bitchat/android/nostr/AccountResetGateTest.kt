package com.bitchat.android.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AccountResetGateTest {
    @Test
    fun supersededLeaseCannotReopenOrRunOwnedMutation() {
        val gate = AccountResetGate()
        val first = gate.begin()
        val second = gate.begin()
        var firstMutationRan = false

        assertFalse(
            gate.runIfCurrent(first) {
                firstMutationRan = true
            }
        )
        assertFalse(firstMutationRan)
        assertTrue(gate.runIfCurrent(second) {})
    }

    @Test
    fun ownedMutationFinishesBeforeNextResetStarts() {
        val gate = AccountResetGate()
        val first = gate.begin()
        val mutationStarted = CountDownLatch(1)
        val releaseMutation = CountDownLatch(1)
        val resetAttempted = CountDownLatch(1)
        val resetFinished = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            executor.submit {
                gate.runIfCurrent(first) {
                    mutationStarted.countDown()
                    assertTrue(releaseMutation.await(2, TimeUnit.SECONDS))
                }
            }
            assertTrue(mutationStarted.await(2, TimeUnit.SECONDS))

            executor.submit {
                resetAttempted.countDown()
                gate.begin()
                resetFinished.countDown()
            }

            assertTrue(resetAttempted.await(2, TimeUnit.SECONDS))
            assertFalse(resetFinished.await(100, TimeUnit.MILLISECONDS))
            releaseMutation.countDown()
            assertTrue(resetFinished.await(2, TimeUnit.SECONDS))
        } finally {
            releaseMutation.countDown()
            executor.shutdownNow()
        }
    }

    @Test(expected = IllegalStateException::class)
    fun ownedMutationCannotRecursivelyBeginReset() {
        val gate = AccountResetGate()
        val lease = gate.begin()

        gate.runIfCurrent(lease) {
            gate.begin()
        }
    }

    @Test
    fun terminalResetRejectsAllLaterResets() {
        val gate = AccountResetGate()
        val terminal = gate.begin(terminal = true)

        assertTrue(terminal != null)
        assertEquals(null, gate.begin(terminal = false))
        assertEquals(null, gate.begin(terminal = true))
    }
}
