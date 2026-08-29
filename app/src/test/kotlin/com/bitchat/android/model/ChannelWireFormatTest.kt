package com.bitchat.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class ChannelWireFormatTest {
    @Test
    fun channelFlagSurvivesBinaryRoundTrip() {
        val original = BitchatMessage(
            sender = "alice",
            content = "hello channel",
            timestamp = Date(1_700_000_000_000L),
            senderPeerID = "aabbccddeeff0011",
            channel = "#general"
        )
        val encoded = original.toBinaryPayload()
        assertNotNull(encoded)
        // Must not be plain UTF-8 of the content alone — that is the mesh leak.
        assertTrue(encoded!!.size > original.content.toByteArray(Charsets.UTF_8).size)

        val decoded = BitchatMessage.fromBinaryPayload(encoded)
        assertNotNull(decoded)
        assertEquals("#general", decoded!!.channel)
        assertEquals("hello channel", decoded.content)
        assertEquals("alice", decoded.sender)
    }

    @Test
    fun plainUtf8MeshPayloadDoesNotParseAsChannelEnvelope() {
        val plain = "just a mesh hello".toByteArray(Charsets.UTF_8)
        assertNull(BitchatMessage.fromBinaryPayload(plain))
    }
}
