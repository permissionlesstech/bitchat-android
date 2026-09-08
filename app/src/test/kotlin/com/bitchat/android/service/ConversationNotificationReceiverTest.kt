package com.bitchat.android.service

import com.bitchat.android.services.mergeConversationDrafts
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationNotificationReceiverTest {
    @Test
    fun `tracked notification reply preserves an existing draft`() {
        assertEquals(
            "unfinished message\nhttps://instagram.com/p/example?igsh=abc",
            mergeConversationDrafts(
                existingDraft = "unfinished message",
                appendedText = "https://instagram.com/p/example?igsh=abc",
                maxChars = 8_000,
            ),
        )
    }

    @Test
    fun `tracked notification reply becomes draft when none exists`() {
        assertEquals(
            "https://instagram.com/p/example?igsh=abc",
            mergeConversationDrafts(
                existingDraft = null,
                appendedText = "https://instagram.com/p/example?igsh=abc",
                maxChars = 8_000,
            ),
        )
    }

    @Test
    fun `tracked reply is retained when existing draft reaches size limit`() {
        val reply = "https://instagram.com/p/example?igsh=abc"
        val merged = mergeConversationDrafts(
            existingDraft = "a".repeat(8_000),
            appendedText = reply,
            maxChars = 8_000,
        )

        assertEquals(8_000, merged.length)
        assertEquals(true, merged.endsWith(reply))
    }
}
