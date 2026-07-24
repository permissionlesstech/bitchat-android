package com.bitchat.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class LocationTelemetryPacketTest {
    @Test
    fun `location payload encodes and decodes correctly`() {
        val encoded = LocationTelemetryPacket.encode(
            latitude = 12.34567890123,
            longitude = 98.76543210987,
            timestampMillis = 1_709_600_000_000L
        )

        assertEquals(20, encoded.size)

        val decoded = LocationTelemetryPacket.decode(encoded)
        assertNotNull(decoded)
        assertEquals(12.34567890123, decoded!!.latitude, 0.000000001)
        assertEquals(98.76543210987, decoded.longitude, 0.000000001)
        assertEquals(1_709_600_000L, decoded.timestampSeconds)

        val packet = LocationTelemetryPacket.buildPacket(
            myPeerID = "1122334455667788",
            latitude = 12.34567890123,
            longitude = 98.76543210987,
            timestampMillis = 1_709_600_000_000L
        )
        assertEquals(MessageType.LOCATION.value, packet.type)
        assertEquals(20, packet.payload.size)
    }
}
