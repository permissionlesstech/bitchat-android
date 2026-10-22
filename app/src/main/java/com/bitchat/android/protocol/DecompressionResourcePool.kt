package com.bitchat.android.protocol

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/**
 * Fair, weighted admission control for decompression allocations.
 *
 * Permits represent memory rather than workers: small packets can proceed concurrently while
 * near-limit packets consume most of the budget. Callers must reserve before allocating any
 * packet-specific compressed copy or expanded output.
 */
internal class DecompressionResourcePool(
    budgetBytes: Long,
    private val unitBytes: Int,
    private val waitTimeoutMs: Long
) {
    private val totalPermits = (budgetBytes / unitBytes).toInt().coerceAtLeast(1)
    private val permits = Semaphore(totalPermits, true)

    fun <T> withReservation(bytes: Long, block: () -> T): T? {
        val requiredPermits = permitsFor(bytes)
        val acquired = try {
            permits.tryAcquire(requiredPermits, waitTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!acquired) return null

        return try {
            block()
        } finally {
            permits.release(requiredPermits)
        }
    }

    internal fun permitsFor(bytes: Long): Int =
        ceil(bytes.coerceAtLeast(1).toDouble() / unitBytes.toDouble())
            .toInt()
            .coerceAtMost(totalPermits)

    internal val availablePermits: Int
        get() = permits.availablePermits()

    companion object {
        private const val DEFAULT_UNIT_BYTES = 256 * 1024
        private const val DEFAULT_WAIT_TIMEOUT_MS = 1_000L
        private const val HEAP_BUDGET_DIVISOR = 8L
        private const val MAX_BUDGET_BYTES = 64L * 1024 * 1024

        fun forRuntime(
            maxHeapBytes: Long = Runtime.getRuntime().maxMemory(),
            maxPacketResourceBytes: Long =
                2L * com.bitchat.android.util.AppConstants.Protocol.MAX_PAYLOAD_LENGTH
        ): DecompressionResourcePool {
            val budget = recommendedBudgetBytes(maxHeapBytes, maxPacketResourceBytes)
            return DecompressionResourcePool(
                budgetBytes = budget,
                unitBytes = DEFAULT_UNIT_BYTES,
                waitTimeoutMs = DEFAULT_WAIT_TIMEOUT_MS
            )
        }

        internal fun recommendedBudgetBytes(
            maxHeapBytes: Long,
            maxPacketResourceBytes: Long
        ): Long = (maxHeapBytes / HEAP_BUDGET_DIVISOR)
            .coerceAtLeast(maxPacketResourceBytes)
            .coerceAtMost(MAX_BUDGET_BYTES.coerceAtLeast(maxPacketResourceBytes))
    }
}
