package com.bitchat.android.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompressionUtilTest {

    /**
     * Valid round-trip through the streaming decompressor
     *
     * Compresses real data with compress() and inflates it with
     * decompress(), verifying byte-exact recovery. Guards against the
     * streaming rewrite breaking legitimate iOS-compatible raw-deflate
     * payloads.
     */
    @Test
    fun `compress and decompress round-trip correctly`() {
        val original = "This is a test message that should compress well. ".repeat(20).toByteArray()
        val compressed = CompressionUtil.compress(original)
        assertNotNull("compression must succeed for repetitive text", compressed)

        val decompressed = CompressionUtil.decompress(compressed!!, original.size)
        assertNotNull("decompression must succeed", decompressed)
        assertArrayEquals("round-trip must be byte-exact", original, decompressed)
    }

    /**
     * Decompression never pre-allocates the claimed original size
     *
     * Regression test for the decompression bomb: decompress() previously
     * allocated ByteArray(originalSize) up front, so a tiny input claiming
     * a ~2 GB output forced a ~2 GB allocation (remote OOM). The streaming
     * implementation must inflate incrementally and fail fast on invalid
     * input without material heap growth.
     */
    @Test
    fun `decompress with huge claimed size does not allocate`() {
        val garbage = byteArrayOf(0x03) // valid raw deflate empty block, inflates to 0 bytes

        System.gc()
        val heapBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

        val result = CompressionUtil.decompress(garbage, Int.MAX_VALUE)

        System.gc()
        val heapAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

        assertNull("invalid input must return null", result)
        val growth = heapAfter - heapBefore
        assertTrue(
            "heap must not grow by more than 64 MB for a 2GB claim (grew ${growth / 1_000_000} MB)",
            growth < 64_000_000
        )
    }

    /**
     * Output exceeding the declared size is rejected
     *
     * If the actual inflated output exceeds the declared originalSize, the
     * declared size was a lie. The decompressor must abort rather than
     * return silently truncated data or keep inflating unboundedly.
     */
    @Test
    fun `output exceeding declared size is rejected`() {
        val original = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA".repeat(10).toByteArray()
        val compressed = CompressionUtil.compress(original)
        assertNotNull(compressed)

        // Declare a size smaller than the real output
        val result = CompressionUtil.decompress(compressed!!, original.size / 2)
        assertNull("output exceeding declared size must be rejected", result)
    }

    /**
     * Non-positive declared sizes are rejected
     */
    @Test
    fun `non-positive original size is rejected`() {
        val compressed = byteArrayOf(0x03)
        assertNull(CompressionUtil.decompress(compressed, 0))
        assertNull(CompressionUtil.decompress(compressed, -1))
        assertNull(CompressionUtil.decompress(compressed, Int.MIN_VALUE))
    }
}
