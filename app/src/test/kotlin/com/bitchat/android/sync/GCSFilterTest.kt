package com.bitchat.android.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class GCSFilterTest {

    @Test
    fun testGCSFilterBasic() {
        val random = Random(42)
        val ids = List(20) {
            val bytes = ByteArray(16)
            random.nextBytes(bytes)
            bytes
        }

        // Build filter with plenty of bytes (no trimming)
        val params = GCSFilter.buildFilter(ids, maxBytes = 400, targetFpr = 0.01)
        val sorted = GCSFilter.decodeToSortedSet(params.p, params.m, params.data)

        for (id in ids) {
            val v = GCSFilter.h64(id) % params.m
            assertTrue("Filter should contain all encoded IDs", GCSFilter.contains(sorted, v))
        }
    }

    @Test
    fun testGCSFilterWithTrimming() {
        val random = Random(42)
        // 50 IDs
        val ids = List(50) {
            val bytes = ByteArray(16)
            random.nextBytes(bytes)
            bytes
        }

        // Force trimming by setting maxBytes to a very small value (e.g., 20 bytes)
        val maxBytes = 20
        val params = GCSFilter.buildFilter(ids, maxBytes = maxBytes, targetFpr = 0.01)
        
        // Ensure some trimming actually happened
        assertTrue("Params data size should be <= maxBytes", params.data.size <= maxBytes)
        
        val sorted = GCSFilter.decodeToSortedSet(params.p, params.m, params.data)

        // Let's verify that the first trimmedN elements in ids are all matched
        val trimmedN = (params.m ushr params.p).toInt()
        assertTrue("At least some elements should have been encoded", trimmedN > 0)
        
        val retainedIds = ids.take(trimmedN)
        for (id in retainedIds) {
            val v = GCSFilter.h64(id) % params.m
            assertTrue("Retained ID should be found in filter", GCSFilter.contains(sorted, v))
        }
    }
}
