package com.bitchat.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DecompressionResourcePoolTest {
    @Test
    fun `small reservations run concurrently and next waits only when budget is full`() {
        val pool = DecompressionResourcePool(
            budgetBytes = 2_000,
            unitBytes = 1_000,
            waitTimeoutMs = 2_000
        )
        val executor = Executors.newFixedThreadPool(3)
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val thirdEntered = CountDownLatch(1)

        try {
            repeat(2) {
                executor.submit {
                    pool.withReservation(1_000) {
                        entered.countDown()
                        release.await()
                    }
                }
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))

            executor.submit {
                pool.withReservation(1_000) {
                    thirdEntered.countDown()
                }
            }
            assertFalse("third reservation must wait while budget is full", thirdEntered.await(100, TimeUnit.MILLISECONDS))

            release.countDown()
            assertTrue("third reservation must proceed after release", thirdEntered.await(1, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `timed admission drops work instead of waiting indefinitely`() {
        val pool = DecompressionResourcePool(
            budgetBytes = 1_000,
            unitBytes = 1_000,
            waitTimeoutMs = 50
        )
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()

        try {
            executor.submit {
                pool.withReservation(1_000) {
                    entered.countDown()
                    release.await()
                }
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertNull(pool.withReservation(1_000) { "unexpected" })
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `permits are released when decode throws`() {
        val pool = DecompressionResourcePool(2_000, 1_000, 50)

        try {
            pool.withReservation(2_000) { error("boom") }
        } catch (_: IllegalStateException) {
            // Expected.
        }

        assertEquals(2, pool.availablePermits)
        assertEquals("ok", pool.withReservation(2_000) { "ok" })
    }

    @Test
    fun `runtime budget is based on heap memory and always admits one maximum packet`() {
        val maxPacketResources = 20L * 1024 * 1024

        assertEquals(
            maxPacketResources,
            DecompressionResourcePool.recommendedBudgetBytes(
                maxHeapBytes = 64L * 1024 * 1024,
                maxPacketResourceBytes = maxPacketResources
            )
        )
        assertEquals(
            32L * 1024 * 1024,
            DecompressionResourcePool.recommendedBudgetBytes(
                maxHeapBytes = 256L * 1024 * 1024,
                maxPacketResourceBytes = maxPacketResources
            )
        )
        assertEquals(
            64L * 1024 * 1024,
            DecompressionResourcePool.recommendedBudgetBytes(
                maxHeapBytes = 2L * 1024 * 1024 * 1024,
                maxPacketResourceBytes = maxPacketResources
            )
        )
    }
}
