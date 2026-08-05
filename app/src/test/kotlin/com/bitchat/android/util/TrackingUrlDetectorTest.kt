package com.bitchat.android.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingUrlDetectorTest {
    @Test
    fun `detects supported tracking query parameters`() {
        listOf("igsh", "fbclid", "gclid", "dclid", "msclkid", "twclid", "ttclid").forEach { key ->
            assertTrue(TrackingUrlDetector.hasTrackingParameter("https://example.com/path?$key=value"))
        }
        assertTrue(TrackingUrlDetector.hasTrackingParameter("https://example.com/?utm_source=chat"))
        assertTrue(TrackingUrlDetector.hasTrackingParameter("https://example.com/?UTM_CUSTOM=value"))
    }

    @Test
    fun `detects empty encoded and mixed case parameter names`() {
        assertTrue(TrackingUrlDetector.hasTrackingParameter("https://instagram.com/p/example?IGSH"))
        assertTrue(TrackingUrlDetector.hasTrackingParameter("https://instagram.com/p/example?foo=1&%69gsh=&bar=2"))
        assertTrue(TrackingUrlDetector.hasTrackingParameter("https://instagram.com/p/example?foo=1&amp;igsh=value"))
    }

    @Test
    fun `ignores tracking text outside exact query parameter names`() {
        listOf(
            "https://example.com/igsh/value",
            "https://example.com/#igsh=value",
            "https://example.com/?other=igsh",
            "https://example.com/?igshid=value",
            "https://example.com/?notutm_source=value",
            "https://example.com/?redirect=https://other.test/?igsh=value",
            "ordinary igsh text",
        ).forEach { url ->
            assertFalse(TrackingUrlDetector.hasTrackingParameter(url))
        }
    }

    @Test
    fun `finds tracked urls in outgoing text`() {
        assertTrue(
            TrackingUrlDetector.containsTrackingUrl(
                "See https://www.instagram.com/reel/example/?foo=1&igsh=abc and let me know.",
            ),
        )
        assertTrue(TrackingUrlDetector.containsTrackingUrl("instagram.com/p/example?igsh=abc"))
        assertTrue(TrackingUrlDetector.containsTrackingUrl("/msg alice www.example.com?utm_medium=chat"))
        assertTrue(TrackingUrlDetector.containsTrackingUrl("example.com:8080/path?utm_source=chat"))
        assertTrue(TrackingUrlDetector.containsTrackingUrl("HTTPS://EXAMPLE.COM/?FBCLID=abc"))
    }

    @Test
    fun `ignores ordinary text emails and safe urls`() {
        listOf(
            "igsh is a parameter name",
            "person@instagram.com?igsh=abc",
            "https://instagram.com/p/example",
            "https://instagram.com/path/igsh?other=value",
            "example.com?campaign=igsh",
        ).forEach { text ->
            assertFalse(TrackingUrlDetector.containsTrackingUrl(text))
        }
    }

    @Test
    fun `handles sentence punctuation and fragments`() {
        assertTrue(TrackingUrlDetector.containsTrackingUrl("Open (https://example.com/?igsh=abc)."))
        assertFalse(TrackingUrlDetector.containsTrackingUrl("Open https://example.com/#page?igsh=abc."))
    }

    @Test
    fun `malformed percent encoding does not throw or warn`() {
        assertFalse(TrackingUrlDetector.hasTrackingParameter("https://example.com/?%zz=value"))
    }
}
