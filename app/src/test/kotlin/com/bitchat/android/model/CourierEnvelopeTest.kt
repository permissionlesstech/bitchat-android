package com.bitchat.android.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CourierEnvelopeTest {
    @Test
    fun `encoding matches fixed iOS wire vector`() {
        val envelope = CourierEnvelope(
            recipientTag = ByteArray(16) { it.toByte() },
            expiry = 0x0102030405060708u,
            ciphertext = byteArrayOf(0xaa.toByte(), 0xbb.toByte(), 0xcc.toByte()),
            copies = 4u,
            prekeyID = 0x11223344u
        )
        assertEquals(
            "010010000102030405060708090a0b0c0d0e0f" +
                "0200080102030405060708" +
                "030003aabbcc" +
                "04000104" +
                "05000411223344",
            envelope.encode()!!.toHex()
        )
    }

    @Test
    fun `copies TLV round trips and legacy omission defaults to one`() {
        val envelope = CourierEnvelope(
            recipientTag = ByteArray(16) { it.toByte() },
            expiry = 123456789u,
            ciphertext = ByteArray(96) { (it + 1).toByte() },
            copies = 4u
        )
        val decoded = CourierEnvelope.decode(envelope.encode()!!)!!
        assertEquals(4u.toUByte(), decoded.copies)
        assertArrayEquals(envelope.recipientTag, decoded.recipientTag)
        assertArrayEquals(envelope.ciphertext, decoded.ciphertext)

        val legacy = envelope.copy(copies = 1u)
        assertEquals(1u.toUByte(), CourierEnvelope.decode(legacy.encode()!!)!!.copies)
    }

    @Test
    fun `prekey id survives decode re-encode and copy changes`() {
        val envelope = CourierEnvelope(
            recipientTag = ByteArray(16) { it.toByte() },
            expiry = 123456789u,
            ciphertext = ByteArray(96) { (it + 1).toByte() },
            copies = 4u,
            prekeyID = 0xfedcba98u
        )

        val decoded = CourierEnvelope.decode(envelope.encode()!!)!!

        assertEquals(0xfedcba98u, decoded.prekeyID)
        assertArrayEquals(envelope.encode(), decoded.copy(copies = 4u).encode())
        assertEquals(0xfedcba98u, decoded.copy(copies = 2u).prekeyID)
    }

    @Test
    fun `invalid or duplicate prekey fields are rejected`() {
        val envelope = CourierEnvelope(ByteArray(16), 1u, ByteArray(32) { 1 }, prekeyID = 7u)
        val encoded = envelope.encode()!!
        val prekeyField = encoded.copyOfRange(encoded.size - 7, encoded.size)

        assertNull(CourierEnvelope.decode(encoded + prekeyField))
        assertNull(CourierEnvelope.decode(encoded.copyOf(encoded.size - 1)))
    }

    @Test
    fun `tag rotates daily and matches adjacent day for clock skew`() {
        val key = ByteArray(32) { 0x2a }
        val now = 10L * 86_400_000L
        val tag = CourierEnvelope.recipientTag(key, CourierEnvelope.epochDay(now) - 1u)
        val envelope = CourierEnvelope(tag, (now + 1_000).toULong(), ByteArray(96) { 1 })
        assertEquals(true, envelope.matchesRecipient(key, now))
        assertEquals(false, envelope.matchesRecipient(ByteArray(32) { 0x2b }, now))
    }

    @Test
    fun `oversized ciphertext is rejected`() {
        val envelope = CourierEnvelope(
            ByteArray(16),
            1u,
            ByteArray(CourierEnvelope.MAX_CIPHERTEXT_BYTES + 1)
        )
        assertNull(envelope.encode())
    }

    @Test
    fun `invalid copy budgets are rejected instead of normalized`() {
        val envelope = CourierEnvelope(ByteArray(16), 1u, ByteArray(32) { 1 })
        assertNull(envelope.copy(copies = 0u).encode())
        assertNull(envelope.copy(copies = 9u).encode())

        val encoded = envelope.copy(copies = 2u).encode()!!
        encoded[encoded.lastIndex] = 0
        assertNull(CourierEnvelope.decode(encoded))
    }

    @Test
    fun `duplicate required fields are rejected`() {
        val envelope = CourierEnvelope(ByteArray(16), 1u, ByteArray(32) { 1 })
        val encoded = envelope.encode()!!
        assertNull(CourierEnvelope.decode(encoded + encoded.copyOfRange(0, 19)))
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
