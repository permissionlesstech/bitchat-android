package com.bitchat.android.nostr

import org.junit.Assert.assertEquals
import org.junit.Test

class GeohashMessageLengthCapTest {

    @Test
    fun leavesShortMessagesUntouched() {
        val shortMsg = "hello geohash"
        assertEquals(shortMsg, GeohashMessageHandler.capGeohashContent(shortMsg))
    }

    @Test
    fun capsOversizedSpamMessages() {
        val huge = "a".repeat(GeohashMessageHandler.MAX_GEOHASH_MESSAGE_CHARS + 500)
        val capped = GeohashMessageHandler.capGeohashContent(huge)
        assertEquals(GeohashMessageHandler.MAX_GEOHASH_MESSAGE_CHARS + 1, capped.length)
        assertEquals("…", capped.takeLast(1))
    }
}
