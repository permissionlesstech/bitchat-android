package com.bitchat.android.cashu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the Cashu token summary decoder: V3 JSON decode, minimal V4
 * CBOR traversal, URI normalization, detection ranges, and adversarial
 * (truncated / garbage / huge) input. The decoder renders attacker-controlled
 * message content, so "never crash" matters as much as "decode correctly".
 *
 * Port of iOS `CashuTokenDecoderTests.swift` (bitchat PR #1376).
 */
class CashuTokenDecoderTest {

    // MARK: - Token builders

    private fun base64Url(data: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    private fun makeV3Token(
        entries: List<Pair<String, List<Int>>>,
        unit: String? = "sat",
        memo: String? = null
    ): String {
        val entriesJson = entries.joinToString(",") { (mint, amounts) ->
            val proofs = amounts.joinToString(",") {
                """{"amount":$it,"id":"009a1f293253e41e","secret":"s","C":"02c"}"""
            }
            """{"mint":"$mint","proofs":[$proofs]}"""
        }
        var json = """{"token":[$entriesJson]"""
        if (unit != null) json += ""","unit":"$unit""""
        if (memo != null) json += ""","memo":"$memo""""
        json += "}"
        return "cashuA" + base64Url(json.toByteArray(Charsets.UTF_8))
    }

    /** Tiny deterministic CBOR encoder (definite lengths only) for building
     *  V4 test tokens without depending on the decoder under test. */
    private object CBOREncode {
        fun head(major: Int, value: Long): ByteArray = when {
            value <= 23 -> byteArrayOf(((major shl 5) or value.toInt()).toByte())
            value <= 0xFF -> byteArrayOf(((major shl 5) or 24).toByte(), value.toByte())
            value <= 0xFFFF -> byteArrayOf(
                ((major shl 5) or 25).toByte(),
                (value shr 8).toByte(), value.toByte()
            )
            else -> byteArrayOf(
                ((major shl 5) or 26).toByte(),
                (value shr 24).toByte(), (value shr 16).toByte(),
                (value shr 8).toByte(), value.toByte()
            )
        }
        fun uint(v: Long): ByteArray = head(0, v)
        fun bytes(b: ByteArray): ByteArray = head(2, b.size.toLong()) + b
        fun text(s: String): ByteArray {
            val utf8 = s.toByteArray(Charsets.UTF_8)
            return head(3, utf8.size.toLong()) + utf8
        }
        fun array(items: List<ByteArray>): ByteArray =
            head(4, items.size.toLong()) + items.reduce { acc, b -> acc + b }
        fun map(pairs: List<Pair<String, ByteArray>>): ByteArray =
            head(5, pairs.size.toLong()) + pairs.map { text(it.first) + it.second }.reduce { acc, b -> acc + b }
    }

    private fun makeV4Token(
        mint: String = "https://mint.example.com",
        unit: String = "sat",
        memo: String? = null,
        amounts: List<Long> = listOf(1, 4)
    ): String {
        val pairs = mutableListOf(
            "m" to CBOREncode.text(mint),
            "u" to CBOREncode.text(unit)
        )
        if (memo != null) pairs.add("d" to CBOREncode.text(memo))
        val proofs = amounts.map { amount ->
            CBOREncode.map(listOf(
                "a" to CBOREncode.uint(amount),
                "s" to CBOREncode.text("secret"),
                "c" to CBOREncode.bytes(byteArrayOf(0x02, 0xAB.toByte(), 0xCD.toByte()))
            ))
        }
        pairs.add("t" to CBOREncode.array(listOf(
            CBOREncode.map(listOf(
                "i" to CBOREncode.bytes(byteArrayOf(0x00, 0xAD.toByte(), 0x26, 0x8C.toByte())),
                "p" to CBOREncode.array(proofs)
            ))
        )))
        return "cashuB" + base64Url(CBOREncode.map(pairs))
    }

    // MARK: - V3 decode

    @Test
    fun `v3 decode valid token`() {
        val token = makeV3Token(listOf("https://mint.example.com" to listOf(2, 8)), memo = "thanks!")
        val info = CashuTokenDecoder.decode(token)
        assertNotNull(info)
        assertEquals("A", info!!.version)
        assertEquals(10L, info.amount)
        assertEquals("sat", info.unit)
        assertEquals("mint.example.com", info.mintHost)
        assertEquals("thanks!", info.memo)
        assertEquals("10 sat", info.displayAmount)
    }

    @Test
    fun `v3 amount sums across entries and proofs`() {
        val token = makeV3Token(listOf(
            "https://a.mint.example" to listOf(1, 2, 4),
            "https://b.mint.example" to listOf(8, 16)
        ))
        val info = CashuTokenDecoder.decode(token)
        assertEquals(31L, info!!.amount)
        // First mint wins for the display host
        assertEquals("a.mint.example", info.mintHost)
    }

    @Test
    fun `v3 missing unit defaults to sat for display`() {
        val token = makeV3Token(listOf("https://mint.example.com" to listOf(5)), unit = null)
        val info = CashuTokenDecoder.decode(token)
        assertNull(info!!.unit)
        assertEquals("5 sat", info.displayAmount)
    }

    @Test
    fun `v3 rejects nonsense amounts`() {
        // Negative and absurd amounts must not poison the sum
        val json = """{"token":[{"mint":"https://mint.example.com","proofs":[""" +
            """{"amount":-5,"id":"x","secret":"s","C":"c"},""" +
            """{"amount":3,"id":"x","secret":"s","C":"c"}]}]}"""
        val token = "cashuA" + base64Url(json.toByteArray())
        assertEquals(3L, CashuTokenDecoder.decode(token)!!.amount)
    }

    @Test
    fun `v3 memo is sanitized for display`() {
        val token = makeV3Token(
            listOf("https://mint.example.com" to listOf(1)),
            memo = "line1line2" + "x".repeat(300)
        )
        val memo = CashuTokenDecoder.decode(token)!!.memo
        assertNotNull(memo)
        assertTrue(memo!!.length <= 80)
    }

    // MARK: - V4 decode

    @Test
    fun `v4 decode valid token`() {
        val token = makeV4Token(memo = "hi", amounts = listOf(2, 8))
        val info = CashuTokenDecoder.decode(token)
        assertNotNull(info)
        assertEquals("B", info!!.version)
        assertEquals(10L, info.amount)
        assertEquals("sat", info.unit)
        assertEquals("mint.example.com", info.mintHost)
        assertEquals("hi", info.memo)
        assertEquals("10 sat", info.displayAmount)
    }

    @Test
    fun `v4 large amount`() {
        val token = makeV4Token(amounts = listOf(2_100_000))
        assertEquals(2_100_000L, CashuTokenDecoder.decode(token)!!.amount)
    }

    // MARK: - URI normalization

    @Test
    fun `bare token strips cashu uri schemes`() {
        val token = makeV3Token(listOf("https://mint.example.com" to listOf(1)))
        assertEquals(token, CashuTokenDecoder.bareToken("cashu:$token"))
        assertEquals(token, CashuTokenDecoder.bareToken("cashu://$token"))
        assertEquals(token, CashuTokenDecoder.bareToken("  $token  "))
    }

    @Test
    fun `decode accepts uri wrapped token`() {
        val token = makeV3Token(listOf("https://mint.example.com" to listOf(7)))
        assertEquals(7L, CashuTokenDecoder.decode("cashu://$token")!!.amount)
    }

    @Test
    fun `padded base64 is accepted`() {
        val json = """{"token":[{"mint":"https://mint.example.com","proofs":[{"amount":4,"id":"x","secret":"s","C":"c"}]}],"unit":"sat"}"""
        val padded = java.util.Base64.getEncoder().encodeToString(json.toByteArray())
        val token = "cashuA$padded"
        assertEquals(4L, CashuTokenDecoder.decode(token)!!.amount)
    }

    // MARK: - Strict mode (the /pay send path)

    @Test
    fun `strict mode rejects garbage`() {
        assertNull(CashuTokenDecoder.decode("cashuA" + "a".repeat(100), strict = true))
        assertNull(CashuTokenDecoder.decode("cashuB" + "a".repeat(100), strict = true))
        assertNull(CashuTokenDecoder.decode("cashuAnot-base64!!!", strict = true))
    }

    @Test
    fun `strict mode accepts valid tokens`() {
        val v3 = makeV3Token(listOf("https://mint.example.com" to listOf(21)))
        val v4 = makeV4Token(amounts = listOf(21))
        assertEquals(21L, CashuTokenDecoder.decode(v3, strict = true)!!.amount)
        assertEquals(21L, CashuTokenDecoder.decode(v4, strict = true)!!.amount)
    }

    @Test
    fun `strict mode rejects structurally incomplete v3 proofs`() {
        // amount but no mint, no keyset id, no secret, no signature
        val json = """{"token":[{"proofs":[{"amount":1}]}]}"""
        val token = "cashuA" + base64Url(json.toByteArray(Charsets.UTF_8))
        assertNull(CashuTokenDecoder.decode(token, strict = true))
        // display mode stays lenient — the amount is still shown
        assertEquals(1L, CashuTokenDecoder.decode(token)?.amount)
    }

    @Test
    fun `strict mode rejects v3 proofs missing secret or signature`() {
        val json = """{"token":[{"mint":"https://mint.example.com","proofs":[{"amount":1,"id":"009a"}]}]}"""
        val token = "cashuA" + base64Url(json.toByteArray(Charsets.UTF_8))
        assertNull(CashuTokenDecoder.decode(token, strict = true))
    }

    @Test
    fun `strict mode accepts structurally complete v3`() {
        assertNotNull(CashuTokenDecoder.decode(
            makeV3Token(listOf("https://mint.example.com" to listOf(5))), strict = true))
    }

    @Test
    fun `strict mode rejects token without amount`() {
        // Valid JSON structure but zero proofs -> no amount -> not sendable
        val json = """{"token":[{"mint":"https://mint.example.com","proofs":[]}],"unit":"sat"}"""
        val token = "cashuA" + base64Url(json.toByteArray())
        assertNull(CashuTokenDecoder.decode(token, strict = true))
    }

    // MARK: - Adversarial input (must never crash, always fail closed)

    @Test
    fun `truncated tokens fail closed`() {
        val v3 = makeV3Token(listOf("https://mint.example.com" to listOf(1)))
        val v4 = makeV4Token()
        for (cut in listOf(6, 10, v3.length / 2, v3.length - 1)) {
            CashuTokenDecoder.decode(v3.take(cut)) // must not throw
        }
        for (cut in listOf(6, 10, v4.length / 2, v4.length - 1)) {
            CashuTokenDecoder.decode(v4.take(cut)) // must not throw
        }
        assertNull(CashuTokenDecoder.decode(v3.take(v3.length / 2), strict = true))
        assertNull(CashuTokenDecoder.decode(v4.take(v4.length - 2), strict = true))
    }

    @Test
    fun `garbage payloads fail closed`() {
        assertNull(CashuTokenDecoder.decode("cashuA"))
        assertNull(CashuTokenDecoder.decode("cashuB"))
        assertNull(CashuTokenDecoder.decode("cashuCaaaaaa"))
        assertNull(CashuTokenDecoder.bareToken("notcashu"))
        assertNull(CashuTokenDecoder.decode("cashuA!!!invalid!!!"))
        // Random base64 that decodes to non-JSON / non-CBOR bytes
        CashuTokenDecoder.decode("cashuA" + base64Url(byteArrayOf(1, 2, 3, 4)))
        CashuTokenDecoder.decode("cashuB" + base64Url(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun `oversized token is rejected before decoding`() {
        val huge = "cashuA" + "a".repeat(CashuTokenDecoder.MAX_TOKEN_LENGTH)
        assertNull(CashuTokenDecoder.bareToken(huge))
        assertNull(CashuTokenDecoder.decode(huge))
    }

    @Test
    fun `indefinite length cbor degrades gracefully`() {
        // 0xBF = indefinite-length map start; the bounded reader must refuse it.
        // Permissive mode: generic chip with no amount. Strict: null.
        // (payload padded so the token clears the 12-char minimum length)
        val payload = byteArrayOf(0xBF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val token = "cashuB" + base64Url(payload)
        val permissive = CashuTokenDecoder.decode(token)
        assertNotNull(permissive)
        assertEquals("B", permissive!!.version)
        assertNull(permissive.amount)
        assertNull(CashuTokenDecoder.decode(token, strict = true))
    }

    @Test
    fun `deeply nested cbor is bounded`() {
        // 100 nested arrays exceeds the depth cap; must fail, not stack-overflow
        var payload = CBOREncode.uint(0)
        repeat(100) { payload = CBOREncode.array(listOf(payload)) }
        val token = "cashuB" + base64Url(payload)
        CashuTokenDecoder.decode(token) // must not throw
        assertNull(CashuTokenDecoder.decode(token, strict = true))
    }

    @Test
    fun `overflow adjacent amounts are capped`() {
        // Two proofs at the sanity cap -> sum exceeds it -> whole decode fails
        val json = """{"token":[{"mint":"https://mint.example.com","proofs":[""" +
            """{"amount":2100000000000000,"id":"x","secret":"s","C":"c"},""" +
            """{"amount":2100000000000000,"id":"x","secret":"s","C":"c"}]}]}"""
        val token = "cashuA" + base64Url(json.toByteArray())
        assertNull(CashuTokenDecoder.decode(token))
    }

    @Test
    fun `control characters in memo are stripped`() {
        val json = """{"token":[{"mint":"https://mint.example.com","proofs":[{"amount":1,"id":"x","secret":"s","C":"c"}]}],"memo":"hi\nthere"}"""
        val token = "cashuA" + base64Url(json.toByteArray())
        val memo = CashuTokenDecoder.decode(token)!!.memo
        assertEquals("hithere", memo)
    }

    @Test
    fun `display amount is null without amount`() {
        assertNull(CashuTokenDecoder.TokenInfo("B", null, null, null, null).displayAmount)
    }
}
