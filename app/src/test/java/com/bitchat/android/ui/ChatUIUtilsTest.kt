package com.bitchat.android.ui

import com.bitchat.android.model.BitchatMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatUIUtilsTest {
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.ROOT).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }

    @Test
    fun `text message metadata separates PoW badge with one space`() {
        val message = BitchatMessage(
            sender = "alice",
            content = "hello",
            timestamp = Date(0),
            powDifficulty = 12,
        )

        assertEquals(
            "00:00:00 ⛨12b",
            formatTextMessageMetadata(message, timeFormatter).text,
        )
    }

    @Test
    fun `text message metadata omits non-positive PoW difficulty`() {
        val message = BitchatMessage(
            sender = "alice",
            content = "hello",
            timestamp = Date(0),
            powDifficulty = 0,
        )

        assertEquals(
            "00:00:00",
            formatTextMessageMetadata(message, timeFormatter).text,
        )
    }
}
