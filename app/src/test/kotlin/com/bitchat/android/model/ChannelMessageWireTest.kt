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
        )!!

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
        )!!
        assertTrue(payload.contentEquals(text.toByteArray(Charsets.UTF_8)))
        assertNull(ChannelMessageWire.decode(payload))
    }

    @Test
    fun `literal channel vector matches the documented layout`() {
        // flags 0x50 (sender peer id + channel), timestamp 1700000000000,
        // id "m", sender "a", content "hi", peer "p", channel "#c".
        val literal = "500000018bcfe56800016d0161000268690170022363"
        val payload = ChannelMessageWire.encode(
            content = "hi",
            mentions = emptyList(),
            channel = "#c",
            sender = "a",
            senderPeerID = "p",
            messageId = "m",
            timestampMillis = 1_700_000_000_000L
        )
        assertEquals(literal, payload?.joinToString("") { "%02x".format(it) })
        assertEquals("#c", ChannelMessageWire.decode(payload!!)?.channel)
        assertEquals("hi", ChannelMessageWire.decode(payload)?.content)
    }

    @Test
    fun `a channel name that would be truncated is not encoded`() {
        val tooLong = "#" + "n".repeat(ChannelMessageWire.MAX_CHANNEL_UTF8_BYTES)
        assertNull(
            ChannelMessageWire.encode(
                content = "hi",
                mentions = emptyList(),
                channel = tooLong,
                sender = "a",
                senderPeerID = "p",
                messageId = "m",
                timestampMillis = 1_700_000_000_000L
            )
        )
    }

    @Test
    fun `an envelope that does not fit the serializer is not sent as public text`() {
        val payload = ChannelMessageWire.encode(
            content = "x".repeat(5000),
            mentions = emptyList(),
            channel = "#c",
            sender = "a",
            senderPeerID = "p",
            messageId = "m",
            timestampMillis = 1_700_000_000_000L
        )
        assertNull(payload)
    }
}
