package com.bitchat.android.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeshPingPayloadTest {
    private val nonce = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

    @Test
    fun `payload round trips with iOS wire layout`() {
        val encoded = MeshPingPayload(nonce, MeshDiagnosticsConstants.TTL).encode()

        assertEquals(MeshDiagnosticsConstants.PAYLOAD_SIZE, encoded.size)
        assertArrayEquals(nonce, encoded.copyOfRange(0, MeshDiagnosticsConstants.NONCE_SIZE))
        assertEquals(MeshDiagnosticsConstants.TTL.toByte(), encoded.last())
        assertEquals(MeshPingPayload(nonce, MeshDiagnosticsConstants.TTL), MeshPingPayload.decode(encoded))
    }

    @Test
    fun `decoder rejects truncation and tolerates trailing bytes`() {
        assertNull(MeshPingPayload.decode(ByteArray(MeshDiagnosticsConstants.PAYLOAD_SIZE - 1)))
        val extended = MeshPingPayload(nonce, MeshDiagnosticsConstants.TTL).encode() + byteArrayOf(99)
        assertEquals(MeshPingPayload(nonce, MeshDiagnosticsConstants.TTL), MeshPingPayload.decode(extended))
    }

    @Test
    fun `hop count uses origin and received ttl`() {
        val payload = MeshPingPayload(nonce, MeshDiagnosticsConstants.TTL)

        assertEquals(1, payload.hopCount(MeshDiagnosticsConstants.TTL))
        assertEquals(3, payload.hopCount((MeshDiagnosticsConstants.TTL - 2u).toUByte()))
    }
}
