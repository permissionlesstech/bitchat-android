package com.bitchat.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class CashuTokenDecoderTest {
    @Test
    fun `v3 sums proofs and sanitizes display metadata`() {
        val token = v3Token(
            """{"token":[{"mint":"https://MINT.example.com/path","proofs":[{"amount":1},{"amount":4},{"amount":16}]}],"unit":"sat","memo":" lunch\n"}"""
        )

        val info = CashuTokenDecoder.decode(token, strict = true)

        assertEquals(21L, info?.amount)
        assertEquals("21 sat", info?.displayAmount)
        assertEquals("mint.example.com", info?.mintHost)
        assertEquals("lunch", info?.memo)
    }

    @Test
    fun `display amount applies currency minor units without changing proof sum`() {
        val usd = CashuTokenDecoder.decode(
            v3Token("""{"token":[{"proofs":[{"amount":900},{"amount":1}]}],"unit":"usd"}"""),
            strict = true
        )
        val jpy = CashuTokenDecoder.decode(
            v3Token("""{"token":[{"proofs":[{"amount":901}]}],"unit":"jpy"}"""),
            strict = true
        )

        assertEquals(901L, usd?.amount)
        assertEquals("9.01 usd", usd?.displayAmount)
        assertEquals("901 jpy", jpy?.displayAmount)
    }

    @Test
    fun `display amount does not assume decimals for custom units`() {
        val token = CashuTokenDecoder.decode(
            v3Token("""{"token":[{"proofs":[{"amount":901}]}],"unit":"usdc"}"""),
            strict = true
        )

        assertEquals("901 usdc", token?.displayAmount)
    }

    @Test
    fun `v4 definite length token decodes strictly`() {
        val token = validV4Token()

        val info = CashuTokenDecoder.decode(token, strict = true)

        assertEquals(21L, info?.amount)
        assertEquals("mint.example.com", info?.mintHost)
    }

    @Test
    fun `strict v4 rejects truncation junk and trailing data`() {
        val valid = validV4Token()
        val truncated = valid.dropLast(12)
        val junk = "cashuB" + "Q".repeat(40)
        val payloadWithTrailingGarbage = decodePayload(valid) + byteArrayOf(0)
        val trailing = "cashuB" + base64Url(payloadWithTrailingGarbage)

        assertNull(CashuTokenDecoder.decode(truncated, strict = true))
        assertNull(CashuTokenDecoder.decode(junk, strict = true))
        assertNull(CashuTokenDecoder.decode(trailing, strict = true))
        assertNotNull(CashuTokenDecoder.decode(junk))
    }

    @Test
    fun `strict decoding rejects missing nonpositive fractional and overflowing amounts`() {
        val values = listOf(
            """{"token":[{"proofs":[]}]}""",
            """{"token":[{"proofs":[{"amount":0}]}]}""",
            """{"token":[{"proofs":[{"amount":1.5}]}]}""",
            """{"token":[{"proofs":[{"amount":2100000000000000},{"amount":1}]}]}"""
        )

        values.forEach { assertNull(CashuTokenDecoder.decode(v3Token(it), strict = true)) }
    }

    @Test
    fun `extracts URI forms as bare deduplicated tokens with a cap`() {
        val first = v3Token("""{"token":[{"proofs":[{"amount":1}]}]}""")
        val second = v3Token("""{"token":[{"proofs":[{"amount":2}]}]}""")
        val text = "cashu:$first and cashu://$first then $second"

        assertEquals(listOf(first, second), CashuTokenDecoder.extractTokens(text, max = 2))
        assertEquals(first, CashuTokenDecoder.bareToken("cashu%3A$first"))
    }

    @Test
    fun `sentence punctuation is not included in extracted token`() {
        val token = v3Token("""{"token":[{"proofs":[{"amount":1}]}]}""")

        assertEquals(listOf(token), CashuTokenDecoder.extractTokens("Redeem $token."))
        assertNull(CashuTokenDecoder.bareToken("$token."))
    }

    @Test
    fun `oversized and deeply nested input fails closed`() {
        assertNull(CashuTokenDecoder.decode("cashuA" + "A".repeat(CashuTokenDecoder.MAX_TOKEN_LENGTH)))
        var nested = byteArrayOf(0)
        repeat(20) { nested = byteArrayOf(0x81.toByte()) + nested }
        assertNull(CashuTokenDecoder.decode("cashuB" + base64Url(nested), strict = true))
    }

    private fun v3Token(json: String): String =
        "cashuA" + base64Url(json.toByteArray(StandardCharsets.UTF_8))

    private fun validV4Token(): String {
        val proofs = listOf(1L, 4L, 16L).map { amount ->
            cborMap(
                "a" to cborUnsigned(amount),
                "s" to cborText("secret"),
                "c" to cborBytes(byteArrayOf(2, 0xab.toByte(), 0xcd.toByte()))
            )
        }
        val payload = cborMap(
            "m" to cborText("https://mint.example.com"),
            "u" to cborText("sat"),
            "t" to cborArray(
                cborMap(
                    "i" to cborBytes(byteArrayOf(0, 0xad.toByte(), 0x26, 0x8c.toByte())),
                    "p" to cborArray(*proofs.toTypedArray())
                )
            )
        )
        return "cashuB" + base64Url(payload)
    }

    private fun cborUnsigned(value: Long) = cborHead(0, value)
    private fun cborText(value: String) =
        cborHead(3, value.toByteArray().size.toLong()) + value.toByteArray()
    private fun cborBytes(value: ByteArray) = cborHead(2, value.size.toLong()) + value
    private fun cborArray(vararg values: ByteArray) =
        cborHead(4, values.size.toLong()) + values.fold(byteArrayOf(), ByteArray::plus)
    private fun cborMap(vararg pairs: Pair<String, ByteArray>) =
        cborHead(5, pairs.size.toLong()) + pairs.fold(byteArrayOf()) { bytes, pair ->
            bytes + cborText(pair.first) + pair.second
        }

    private fun cborHead(major: Int, value: Long): ByteArray = when (value) {
        in 0..23 -> byteArrayOf(((major shl 5) or value.toInt()).toByte())
        in 24..255 -> byteArrayOf(((major shl 5) or 24).toByte(), value.toByte())
        else -> byteArrayOf(
            ((major shl 5) or 25).toByte(),
            (value ushr 8).toByte(),
            value.toByte()
        )
    }

    private fun decodePayload(token: String): ByteArray {
        val encoded = token.substring(6)
        return Base64.getUrlDecoder().decode(encoded + "=".repeat((4 - encoded.length % 4) % 4))
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
