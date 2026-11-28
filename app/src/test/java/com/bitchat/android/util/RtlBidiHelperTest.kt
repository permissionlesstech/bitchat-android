package com.bitchat.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RtlBidiHelperTest {

    @Test
    fun isolatesMentionsChannelsAndUrlsInArabicMessage() {
        val input = "مرحبا @alice في #9q8yy https://bitchat.free"
        val output = RtlBidiHelper.isolateMixedChatTokens(input)

        assertTrue(output.contains("\u2066@alice\u2069"))
        assertTrue(output.contains("\u2066#9q8yy\u2069"))
        assertTrue(output.contains("\u2066https://bitchat.free\u2069"))
    }

    @Test
    fun keepsDisambiguationSuffixInsideTheMentionIsolate() {
        val output = RtlBidiHelper.isolateMixedChatTokens("@alice#abcd")
        assertEquals("\u2066@alice#abcd\u2069", output)
    }

    @Test
    fun doesNotIsolateEmailDomains() {
        val input = "user@example.invalid"
        assertEquals(input, RtlBidiHelper.isolateMixedChatTokens(input))
    }

    @Test
    fun leavesParenthesesOutsideUrlIsolate() {
        val output = RtlBidiHelper.isolateMixedChatTokens("(https://example.invalid).")
        assertEquals("(\u2066https://example.invalid\u2069).", output)
    }

    @Test
    fun leavesEmptyStringUnchanged() {
        assertEquals("", RtlBidiHelper.isolateMixedChatTokens(""))
        assertEquals("", RtlBidiHelper.isolateLtr(""))
    }

    @Test
    fun manifestDeclaresSupportsRtlTrue() {
        val candidates = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
        val manifest = candidates.first { it.exists() }.readText()
        assertTrue(manifest.contains("android:supportsRtl=\"true\""))
    }
}
