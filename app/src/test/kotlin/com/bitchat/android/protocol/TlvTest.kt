package com.bitchat.android.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TlvTest {

    @Test
    fun `writer and reader support one two and four byte lengths`() {
        val oneByteValue = byteArrayOf(0x7f)
        val twoByteValue = ByteArray(256) { it.toByte() }
        val fourByteValue = ByteArray(70_000) { (it % 251).toByte() }

        val encoded = TlvWriter()
            .put(0x01, oneByteValue, TlvLengthSize.ONE_BYTE)
            .put(0x02, twoByteValue, TlvLengthSize.TWO_BYTES)
            .put(0x03, fourByteValue, TlvLengthSize.FOUR_BYTES)
            .toByteArray()

        val fields = TlvReader.decode(encoded, TlvLengthSize.ONE_BYTE) { type ->
            when (type) {
                0x02 -> TlvLengthSize.TWO_BYTES
                0x03 -> TlvLengthSize.FOUR_BYTES
                else -> TlvLengthSize.ONE_BYTE
            }
        }

        assertEquals(3, fields!!.size)
        assertArrayEquals(oneByteValue, fields[0].value)
        assertArrayEquals(twoByteValue, fields[1].value)
        assertArrayEquals(fourByteValue, fields[2].value)
    }

    @Test
    fun `unknown tlv policy skips or fails consistently`() {
        val encoded = TlvWriter()
            .put(0x01, byteArrayOf(1), TlvLengthSize.ONE_BYTE)
            .put(0x7f, byteArrayOf(9), TlvLengthSize.ONE_BYTE)
            .put(0x02, byteArrayOf(2), TlvLengthSize.ONE_BYTE)
            .toByteArray()

        val skipped = TlvReader.decode(
            data = encoded,
            defaultLengthSize = TlvLengthSize.ONE_BYTE,
            unknownPolicy = UnknownTlvPolicy.SKIP,
            knownTypes = setOf(0x01, 0x02)
        )
        val failed = TlvReader.decode(
            data = encoded,
            defaultLengthSize = TlvLengthSize.ONE_BYTE,
            unknownPolicy = UnknownTlvPolicy.FAIL,
            knownTypes = setOf(0x01, 0x02)
        )

        assertEquals(listOf(0x01, 0x02), skipped!!.map { it.type })
        assertNull(failed)
    }

    @Test
    fun `reader rejects truncated values and writer rejects oversized values`() {
        val truncated = byteArrayOf(0x01, 0x03, 0x41)

        assertNull(TlvReader.decode(truncated, TlvLengthSize.ONE_BYTE))

        try {
            TlvWriter().put(0x01, ByteArray(256), TlvLengthSize.ONE_BYTE)
            throw AssertionError("Expected oversized one-byte TLV value to fail")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
