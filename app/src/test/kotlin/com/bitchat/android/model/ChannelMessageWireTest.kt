package com.bitchat.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelMessageWireTest {

    @Test
    fun `a channel message round trips with its channel and mentions`() {
        val payload = ChannelMessageWire.encode(
            content = "see you at the gate",
            mentions = listOf("peer-b"),
            channel = "#familiarodrigues",
            sender = "ada",
            senderPeerID = "aa11",
            messageId = "MSG-1",
            timestampMillis = 1_700_000_000_000L
        )

        val decoded = ChannelMessageWire.decode(payload)
        assertEquals("#familiarodrigues", decoded?.channel)
        assertEquals("see you at the gate", decoded?.content)
        assertEquals(listOf("peer-b"), decoded?.mentions)
        assertEquals("ada", decoded?.sender)
        assertEquals("aa11", decoded?.senderPeerID)
        assertEquals("MSG-1", decoded?.id)
    }

    @Test
    fun `public mesh text stays raw and is not read as a channel message`() {
        val text = "hello from the public mesh, #familiarodrigues is just words"
        val payload = ChannelMessageWire.encode(
            content = text,
            mentions = emptyList(),
            channel = null,
            sender = "ada",
            senderPeerID = "aa11",
            messageId = "MSG-2",
            timestampMillis = 1_700_000_000_000L
        )
        assertTrue(payload.contentEquals(text.toByteArray(Charsets.UTF_8)))
        assertNull(ChannelMessageWire.decode(payload))
    }
}
