package com.bitchat.android.ui

import com.bitchat.android.model.BitchatMessage
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatUserSheetTest {

    @Test
    fun `a message on the mesh timeline is found`() {
        val message = message("a")

        assertEquals(message, timelineMessage("a", listOf(message), emptyMap()))
    }

    @Test
    fun `a message in a channel is found`() {
        val message = message("b")

        assertEquals(
            message,
            timelineMessage("b", emptyList(), mapOf("#general" to listOf(message("x"), message)))
        )
    }

    @Test
    fun `a message that is gone offers user actions only`() {
        assertNull(timelineMessage("gone", listOf(message("a")), mapOf("#general" to listOf(message("b")))))
    }

    @Test
    fun `no message id means no message`() {
        assertNull(timelineMessage(null, listOf(message("a")), emptyMap()))
    }

    private fun message(id: String) = BitchatMessage(
        id = id,
        sender = "sender",
        content = "content",
        timestamp = Date(0),
    )
}
