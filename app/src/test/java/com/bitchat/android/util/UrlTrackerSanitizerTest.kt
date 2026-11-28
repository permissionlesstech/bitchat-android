package com.bitchat.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlTrackerSanitizerTest {

    @Test
    fun stripsInstagramIgshAndUtmParameters() {
        val url = "https://www.instagram.com/p/ABC123/?igsh=MzRlODBiNWFlZA==&utm_source=ig_web_copy_link"
        val result = UrlTrackerSanitizer.inspect(url)

        assertTrue(result.hasTrackers)
        assertEquals(listOf("igsh", "utm_source"), result.removedTrackers)
        assertEquals("https://www.instagram.com/p/ABC123/", result.sanitizedUrl)
    }

    @Test
    fun preservesNonTrackingQueryParametersAndFragment() {
        val url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&si=abcdef123456&t=42#comments"
        val result = UrlTrackerSanitizer.inspect(url)

        assertTrue(result.hasTrackers)
        assertEquals(listOf("si"), result.removedTrackers)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42#comments", result.sanitizedUrl)
    }

    @Test
    fun decodesPercentEncodedTrackerKeys() {
        val url = "https://example.com/?%75tm_source=campaign&keep=1"
        val result = UrlTrackerSanitizer.inspect(url)

        assertTrue(result.hasTrackers)
        assertEquals(listOf("utm_source"), result.removedTrackers)
        assertEquals("https://example.com/?keep=1", result.sanitizedUrl)
    }

    @Test
    fun leavesCleanUrlsUnchanged() {
        val url = "https://example.com/search?q=bitchat&page=2"
        val result = UrlTrackerSanitizer.inspect(url)

        assertFalse(result.hasTrackers)
        assertTrue(result.removedTrackers.isEmpty())
        assertEquals(url, result.sanitizedUrl)
    }
}
