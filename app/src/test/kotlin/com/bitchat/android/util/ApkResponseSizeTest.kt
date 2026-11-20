package com.bitchat.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkResponseSizeTest {
    @Test
    fun `an overflowing numeric total is not an unknown total`() {
        assertNull(parseContentRange("bytes 0-1/9223372036854775808"))
        assertEquals(ContentRange(0, 1, null), parseContentRange("bytes 0-1/*"))
    }

    @Test
    fun `range span and content length must agree before append`() {
        val range = ContentRange(10, 19, 20)
        assertNull(apkResponseSize(range, 9, 10, null))
        assertNull(apkResponseSize(range, 11, 10, null))
        assertEquals(ApkResponseSize(20, 10), apkResponseSize(range, 10, 10, null))
        assertEquals(ApkResponseSize(20, 10), apkResponseSize(range, -1, 10, null))
    }

    @Test
    fun `overflowing span and inferred total fail closed`() {
        assertNull(apkResponseSize(ContentRange(0, Long.MAX_VALUE, null), -1, 0, null))
        assertNull(apkResponseSize(ContentRange(1, Long.MAX_VALUE, null), Long.MAX_VALUE, 1, null))
        assertEquals(ApkResponseSize(3, 2), apkResponseSize(ContentRange(1, 2, null), 2, 1, null))
    }

    @Test
    fun `chunked response cannot write past the advertised range`() {
        val size = apkResponseSize(ContentRange(10, 19, null), -1, 10, null)!!
        val budget = ApkResponseByteBudget(size.responseBytes)
        assertTrue(budget.accept(6))
        assertFalse(budget.isComplete())
        assertFalse(budget.accept(5))
        assertTrue(budget.accept(4))
        assertTrue(budget.isComplete())
        assertFalse(budget.accept(1))
    }

    @Test
    fun `a full response ignores saved sizes and a resumed range supplies its own limit`() {
        val size = apkResponseSize(null, -1, 0, 20)!!
        assertEquals(ApkResponseSize(0, null), size)
        val resumed = apkResponseSize(ContentRange(10, 19, 20), -1, 10, 20)!!
        assertEquals(ApkResponseSize(20, 10), resumed)
        assertTrue(ApkResponseByteBudget(resumed.responseBytes).accept(10))
    }

    @Test
    fun `full replacement ignores old file size and byte count`() {
        assertEquals(ApkResponseSize(12, 12), apkResponseSize(null, 12, 0, 100))
        val budget = ApkResponseByteBudget(12)
        assertTrue(budget.accept(12))
        assertFalse(budget.accept(1))
    }

    @Test
    fun `truncated chunked range is incomplete even without a file total`() {
        val size = apkResponseSize(ContentRange(10, 19, null), -1, 10, null)!!
        val budget = ApkResponseByteBudget(size.responseBytes)
        assertTrue(budget.accept(9))
        assertFalse(budget.isComplete())
    }
}
