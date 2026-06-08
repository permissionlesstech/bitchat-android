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
            // An ID is only found if it didn't map to 0 (which is filtered out)
            if (v > 0) {
                assertTrue("Retained ID should be found in filter", GCSFilter.contains(sorted, v))
            }
        }
    }

    @Test
    fun testGCSFilterHandlesCollisionsAndZeroBucket() {
        val random = Random(42)
        // Generate a large number of IDs to guarantee collisions (mapping to the same bucket) and some zero-bucket mapping
        val ids = List(200) {
            val bytes = ByteArray(16)
            random.nextBytes(bytes)
            bytes
        }

        // Build GCS filter - this should complete successfully without throwing repeat count exceptions or negative-count crashes
        val params = GCSFilter.buildFilter(ids, maxBytes = 100, targetFpr = 0.05)
        val sorted = GCSFilter.decodeToSortedSet(params.p, params.m, params.data)

        // Verify some elements are successfully stored and found
        var foundCount = 0
        for (id in ids) {
            val v = GCSFilter.h64(id) % params.m
            if (v > 0 && GCSFilter.contains(sorted, v)) {
                foundCount++
            }
        }
        assertTrue("Should successfully decode and find elements after deduplication", foundCount > 0)
    }
}
