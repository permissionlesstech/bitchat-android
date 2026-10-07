package com.bitchat.android.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Tlv16CodecTest {
    @Test
    fun `codec preserves ordered fields and unknown types`() {
        val encoded = requireNotNull(
            Tlv16Codec.encode(
                Tlv16Codec.Field(1, byteArrayOf(1, 2)),
                Tlv16Codec.Field(0x7F, byteArrayOf(3))
            )
        )

        val decoded = requireNotNull(Tlv16Codec.decode(encoded))
        assertEquals(listOf(1, 0x7F), decoded.map { it.type })
        assertArrayEquals(byteArrayOf(1, 2), decoded[0].value)
        assertArrayEquals(byteArrayOf(3), decoded[1].value)
    }

    @Test
    fun `codec rejects truncated framing and oversized values`() {
        assertNull(Tlv16Codec.decode(byteArrayOf(1, 0)))
        assertNull(Tlv16Codec.decode(byteArrayOf(1, 0, 2, 1)))
        assertNull(Tlv16Codec.encode(Tlv16Codec.Field(1, ByteArray(0x1_0000))))
    }
}
