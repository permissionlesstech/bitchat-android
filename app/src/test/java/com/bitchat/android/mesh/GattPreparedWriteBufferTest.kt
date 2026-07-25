package com.bitchat.android.mesh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [GattPreparedWriteBuffer], the pure reassembly logic behind the
 * fix for issue #90 (iOS -> Android messages over ~157 chars silently dropped
 * because BLE long/prepared writes were never reassembled on the GATT server).
 */
class GattPreparedWriteBufferTest {

    private lateinit var buffer: GattPreparedWriteBuffer

    @Before
    fun setup() {
        buffer = GattPreparedWriteBuffer()
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun `single chunk is returned verbatim on execute`() {
        val device = "AA:BB:CC:DD:EE:01"
        val payload = bytes(1, 2, 3, 4, 5)
        assertTrue(buffer.append(device, 0, payload))
        assertArrayEquals(payload, buffer.execute(device))
    }

    @Test
    fun `multiple in-order chunks reassemble to the concatenation`() {
        val device = "AA:BB:CC:DD:EE:02"
        val c0 = bytes(10, 11, 12)
        val c1 = bytes(20, 21, 22)
        val c2 = bytes(30, 31)
        assertTrue(buffer.append(device, 0, c0))
        assertTrue(buffer.append(device, 3, c1))
        assertTrue(buffer.append(device, 6, c2))

        val expected = c0 + c1 + c2
        assertArrayEquals(expected, buffer.execute(device))
    }

    /**
     * The key regression guard: chunks are placed by offset, so an implementation
     * that simply appends in arrival order (ignoring offset) would produce the
     * wrong payload. Feeding chunks out of arrival order must still reassemble
     * correctly.
     */
    @Test
    fun `chunks delivered out of order reassemble by offset not arrival order`() {
        val device = "AA:BB:CC:DD:EE:03"
        val first = bytes(1, 2, 3, 4)   // belongs at offset 0
        val second = bytes(5, 6, 7, 8)  // belongs at offset 4

        // Deliver the later offset first
        assertTrue(buffer.append(device, 4, second))
        assertTrue(buffer.append(device, 0, first))

        val expected = bytes(1, 2, 3, 4, 5, 6, 7, 8)
        assertArrayEquals(expected, buffer.execute(device))
    }

    @Test
    fun `reassembles a payload larger than a typical ATT MTU`() {
        val device = "AA:BB:CC:DD:EE:04"
        val total = 600 // > 157 chars / > single-MTU write from the issue
        val full = ByteArray(total) { (it % 256).toByte() }
        val chunkSize = 180 // mimic MTU-sized fragments
        var offset = 0
        while (offset < total) {
            val end = minOf(offset + chunkSize, total)
            val slice = full.copyOfRange(offset, end)
            assertTrue(buffer.append(device, offset, slice))
            offset = end
        }
        assertArrayEquals(full, buffer.execute(device))
    }

    @Test
    fun `devices are isolated from one another`() {
        val a = "AA:BB:CC:DD:EE:0A"
        val b = "AA:BB:CC:DD:EE:0B"
        val payloadA = bytes(1, 1, 1)
        val payloadB = bytes(9, 9, 9, 9)

        buffer.append(a, 0, payloadA)
        buffer.append(b, 0, payloadB)

        assertArrayEquals(payloadA, buffer.execute(a))
        // Draining device A must not affect device B's buffer
        assertArrayEquals(payloadB, buffer.execute(b))
    }

    @Test
    fun `cancel discards buffered chunks and execute then yields null`() {
        val device = "AA:BB:CC:DD:EE:05"
        buffer.append(device, 0, bytes(1, 2, 3))
        buffer.cancel(device)
        assertNull(buffer.execute(device))
    }

    @Test
    fun `execute with nothing buffered yields null`() {
        assertNull(buffer.execute("never-seen"))
    }

    @Test
    fun `execute clears the buffer so a second execute yields null`() {
        val device = "AA:BB:CC:DD:EE:06"
        buffer.append(device, 0, bytes(7, 7))
        assertArrayEquals(bytes(7, 7), buffer.execute(device))
        assertNull(buffer.execute(device))
        assertEquals(0, buffer.activeDeviceCount())
    }

    @Test
    fun `oversize write is rejected and dropped`() {
        val small = GattPreparedWriteBuffer(maxPayloadSize = 16)
        val device = "AA:BB:CC:DD:EE:07"
        assertTrue(small.append(device, 0, ByteArray(10)))
        // This chunk would push the payload past the 16-byte cap
        assertFalse(small.append(device, 10, ByteArray(10)))
        // Once overflowed the whole payload is dropped
        assertNull(small.execute(device))
    }

    @Test
    fun `negative offset is rejected`() {
        val device = "AA:BB:CC:DD:EE:08"
        assertFalse(buffer.append(device, -1, bytes(1, 2, 3)))
    }

    @Test
    fun `writes exactly at the cap are accepted`() {
        val small = GattPreparedWriteBuffer(maxPayloadSize = 16)
        val device = "AA:BB:CC:DD:EE:09"
        assertTrue(small.append(device, 0, ByteArray(16) { it.toByte() }))
        val result = small.execute(device)
        assertEquals(16, result?.size)
    }
}
