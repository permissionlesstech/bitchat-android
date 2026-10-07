package com.bitchat.android.mesh

import com.bitchat.android.model.BitchatMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelMessagePayloadTest {
    @Test
    fun `mesh text retains the existing UTF8 representation`() {
        assertArrayEquals("hello".toByteArray(), encodePublicOrChannelMessage(
            "hello", emptyList(), null, "sender", "1111222233334444"
        ))
    }

    @Test
    fun `channel routing survives encoding with its content`() {
        val encoded = encodePublicOrChannelMessage(
            "hello", listOf("peer"), "#test", "sender", "1111222233334444"
        )
        assertNotNull(encoded)
        val decoded = BitchatMessage.fromBinaryPayload(encoded!!)
        assertNotNull(decoded)
        assertEquals("#test", decoded!!.channel)
        assertEquals("hello", decoded.content)
        assertEquals(listOf("peer"), decoded.mentions)
    }

    @Test
    fun `oversized channel envelopes cannot escape as public mesh text`() {
        assertNull(encodePublicOrChannelMessage(
            "x".repeat(5_000), emptyList(), "#test", "sender", "1111222233334444"
        ))
    }
}
