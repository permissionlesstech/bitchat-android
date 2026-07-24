package com.bitchat.android.protocol

import org.junit.Assert.*
import org.junit.Test

class LocationVerifyPacketTest {

    @Test
    fun testEncodeAndDecodeRequest() {
        val now = System.currentTimeMillis()
        val payload = LocationVerifyPacket.encode(LocationVerifyPacket.Action.REQUEST, now)
        val decoded = LocationVerifyPacket.decode(payload)

        assertNotNull(decoded)
        assertEquals(LocationVerifyPacket.Action.REQUEST, decoded?.action)
        assertEquals(now / 1000L, decoded?.timestampSeconds)
    }

    @Test
    fun testEncodeAndDecodeAccept() {
        val now = System.currentTimeMillis()
        val payload = LocationVerifyPacket.encode(LocationVerifyPacket.Action.ACCEPT, now)
        val decoded = LocationVerifyPacket.decode(payload)

        assertNotNull(decoded)
        assertEquals(LocationVerifyPacket.Action.ACCEPT, decoded?.action)
        assertEquals(now / 1000L, decoded?.timestampSeconds)
    }

    @Test
    fun testEncodeAndDecodeReject() {
        val now = System.currentTimeMillis()
        val payload = LocationVerifyPacket.encode(LocationVerifyPacket.Action.REJECT, now)
        val decoded = LocationVerifyPacket.decode(payload)

        assertNotNull(decoded)
        assertEquals(LocationVerifyPacket.Action.REJECT, decoded?.action)
        assertEquals(now / 1000L, decoded?.timestampSeconds)
    }

    @Test
    fun testBuildPacket() {
        val sender = "1122334455667788"
        val recipient = "aabbccddeeff0011"
        val packet = LocationVerifyPacket.buildPacket(
            myPeerID = sender,
            targetPeerID = recipient,
            action = LocationVerifyPacket.Action.REQUEST
        )

        assertEquals(MessageType.LOCATION_VERIFY.value, packet.type)
        assertNotNull(packet.recipientID)
        val decoded = LocationVerifyPacket.decode(packet.payload)
        assertEquals(LocationVerifyPacket.Action.REQUEST, decoded?.action)
    }
}
