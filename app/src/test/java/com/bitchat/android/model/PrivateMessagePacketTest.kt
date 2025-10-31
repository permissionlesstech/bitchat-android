package com.bitchat.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PrivateMessagePacketTest {

    @Test
    fun encodeDecode1B_roundtrip() {
        val pm = PrivateMessagePacket(messageID = "abc-123", content = "hello cashu")
        val tlv = pm.encode()
        assertNotNull(tlv)
        val decoded = PrivateMessagePacket.decode(tlv!!)
        assertNotNull(decoded)
        assertEquals(pm.messageID, decoded!!.messageID)
        assertEquals(pm.content, decoded.content)
    }

    @Test
    fun encodeDecode2B_roundtrip() {
        val longContent = "x".repeat(300)
        val pm = PrivateMessagePacket(messageID = "id-300", content = longContent)
        val tlv = pm.encode2B()
        assertNotNull(tlv)
        val decoded = PrivateMessagePacket.decode(tlv!!)
        assertNotNull(decoded)
        assertEquals(pm.messageID, decoded!!.messageID)
        assertEquals(pm.content, decoded.content)
    }
}
