package com.bitchat.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class LocationTelemetryPacketTest {
    @Test
    fun `location payload encodes and decodes correctly`() {
        val encoded = LocationTelemetryPacket.encode(
            latitude = 12.345678,
            longitude = 98.765432,
            timestampMillis = 1_709_600_000_000L
        )

        assertEquals(12, encoded.size)

        val decoded = LocationTelemetryPacket.decode(encoded)
        assertNotNull(decoded)
        assertEquals(12.345678f, decoded!!.latitude, 0.0001f)
        assertEquals(98.765434f, decoded.longitude, 0.0001f)
        assertEquals(1_709_600_000L, decoded.timestampSeconds)

        val packet = LocationTelemetryPacket.buildPacket(
            myPeerID = "1122334455667788",
            latitude = 12.345678,
            longitude = 98.765432,
            timestampMillis = 1_709_600_000_000L
        )
        assertEquals(MessageType.LOCATION.value, packet.type)
        assertEquals(12, packet.payload.size)
    }
}
