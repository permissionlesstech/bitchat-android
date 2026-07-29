package com.bitchat.android.cashu

import com.google.gson.JsonParser
import java.net.URI
import java.util.Base64

/**
 * Decodes Cashu ecash tokens (V3 `cashuA` = base64url JSON, V4 `cashuB` =
 * base64url CBOR) just far enough to summarize them for the UI: total
 * amount, unit, mint host, and memo. The app never contacts a mint — tokens
 * are bearer strings and redemption is delegated to an external wallet.
 *
 * This parses attacker-controlled message content, so every path is
 * bounds-checked, size-capped, and returns null instead of throwing.
 *
 * Port of iOS `CashuTokenDecoder.swift` (bitchat PR #1376).
 */
object CashuTokenDecoder {

    data class TokenInfo(
        /** Token serialization version: "A" (JSON) or "B" (CBOR). */
        val version: String,
        /** Sum of all proof amounts; null when no valid amounts were found. */
        val amount: Long?,
        /** Currency unit as declared by the token (commonly "sat"), if any. */
        val unit: String?,
        /** Host of the (first) mint URL, for display. */
        val mintHost: String?,
        /** Optional sender memo, sanitized for display. */
        val memo: String?,
        /**
         * False when the token is structurally incomplete (V3: missing mint or
         * proofs lacking secret/signature). Display stays lenient; strict mode
         * rejects these as unsendable.
         */
        val complete: Boolean = true
    ) {
        /** "500 sat" style summary, defaulting the unit to sats per NUT-00. */
        val displayAmount: String?
            get() = amount?.let { "$it ${unit ?: "sat"}" }
    }

    /** Upper bound on accepted token length in characters. Real tokens are a
     *  few KB; anything much bigger is abuse we shouldn't spend CPU on. */
    const val MAX_TOKEN_LENGTH = 60_000
    private const val MIN_TOKEN_LENGTH = 12
    /** Per-proof and total amount sanity caps (order of total sats in existence). */
    private const val MAX_AMOUNT = 2_100_000_000_000_000L

    private val tokenCharset = Regex("^[A-Za-z0-9\\-_+/=.]+$")

    // MARK: - Public API

    /**
     * Extracts the bare `cashuA…`/`cashuB…` token from raw text that may be
     * a `cashu:`/`cashu://` URI and/or percent-encoded. Returns null when the
     * input doesn't look like a Cashu token at all.
     */
    fun bareToken(from: String): String? {
        var token = from.trim()
        val lower = token.lowercase()
        token = when {
            lower.startsWith("cashu://") -> token.substring(8)
            lower.startsWith("cashu:") -> token.substring(6)
            else -> token
        }
        if (token.contains('%')) {
            token = percentDecode(token) ?: return null
        }
        if (token.length < MIN_TOKEN_LENGTH || token.length > MAX_TOKEN_LENGTH) return null
        if (!token.startsWith("cashuA") && !token.startsWith("cashuB")) return null
        if (!tokenCharset.matches(token)) return null
        return token
    }

    /**
     * Decodes a token (raw or `cashu:` URI form) into a display summary.
     *
     * In the default (permissive) mode this is for *rendering*: V3 tokens
     * must parse as JSON, but a V4 token whose CBOR we cannot walk still
     * returns a generic [TokenInfo] (version "B", no amount) because the
     * payload may use encodings this minimal reader doesn't support — an
     * unknown chip is fine for display.
     *
     * In `strict` mode (used by the `/pay` SEND path) there is no permissive
     * fallback: the token must cleanly decode to a known version *and* carry
     * a positive amount, otherwise this returns null. This stops base64 junk
     * and truncated V4 tokens from being relayed as if they were valid money.
     */
    fun decode(raw: String, strict: Boolean = false): TokenInfo? {
        val token = bareToken(raw) ?: return null
        val version = token.substring(5, 6)
        val payload = base64UrlDecode(token.substring(6))?.takeIf { it.isNotEmpty() } ?: return null
        val decoded: TokenInfo? = when (version) {
            "A" -> decodeV3(payload)
            "B" -> {
                val walked = decodeV4(payload)
                when {
                    walked != null -> walked
                    strict -> return null // couldn't cleanly walk the CBOR — refuse to send it
                    else -> TokenInfo(version = "B", amount = null, unit = null, mintHost = null, memo = null)
                }
            }
            else -> return null
        }
        val info = decoded ?: return null
        if (strict) {
            // A sendable token must resolve to a positive, sane amount and be
            // structurally complete (mint + full proofs) — an amount alone does
            // not make an unredeemable payload sendable.
            val amount = info.amount ?: return null
            if (amount <= 0) return null
            if (!info.complete) return null
        }
        return info
    }

    // MARK: - Percent decoding (only %XX; '+' is a valid base64 char and must survive)

    private fun percentDecode(input: String): String? {
        val bytes = ArrayList<Byte>(input.length)
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '%') {
                if (i + 2 > input.length - 1) return null
                val h1 = input[i + 1].digitToIntOrNull(16) ?: return null
                val h2 = input[i + 2].digitToIntOrNull(16) ?: return null
                bytes.add(((h1 shl 4) or h2).toByte())
                i += 3
            } else {
                bytes.add(c.code.toByte())
                i += 1
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    // MARK: - Base64url

    private fun base64UrlDecode(input: String): ByteArray? {
        var s = input.replace('-', '+').replace('_', '/').replace("=", "")
        val remainder = s.length % 4
        if (remainder == 1) return null
        if (remainder > 0) s += "=".repeat(4 - remainder)
        return try {
            Base64.getDecoder().decode(s)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    // MARK: - V3 (JSON)

    private fun decodeV3(payload: ByteArray): TokenInfo? {
        val obj = try {
            JsonParser.parseString(String(payload, Charsets.UTF_8)).asJsonObject
        } catch (e: Exception) {
            return null
        }
        val entries = try {
            obj.getAsJsonArray("token")
        } catch (e: Exception) {
            null
        } ?: return null
        if (entries.size() == 0) return null

        var total = 0L
        var sawAmount = false
        var complete = true
        var mintHost: String? = null
        for (entryEl in entries) {
            val entry = try { entryEl.asJsonObject } catch (e: Exception) { continue }
            val mint = entry.get("mint")?.takeIf { it.isJsonPrimitive }?.asString
            if (mint.isNullOrBlank()) complete = false
            if (mintHost == null && mint != null) mintHost = sanitizedHost(mint)
            val proofs = try { entry.getAsJsonArray("proofs") } catch (e: Exception) { null } ?: continue
            for (proofEl in proofs) {
                val proof = try { proofEl.asJsonObject } catch (e: Exception) { continue }
                val amountEl = proof.get("amount")?.takeIf { it.isJsonPrimitive } ?: continue
                val value = try { amountEl.asLong } catch (e: Exception) { continue }
                if (value <= 0 || value > MAX_AMOUNT) continue
                // An amount without keyset id / secret / signature is not redeemable
                if (proof.get("id")?.takeIf { it.isJsonPrimitive }?.asString.isNullOrBlank() ||
                    proof.get("secret")?.takeIf { it.isJsonPrimitive }?.asString.isNullOrBlank() ||
                    proof.get("C")?.takeIf { it.isJsonPrimitive }?.asString.isNullOrBlank()
                ) complete = false
                total += value
                if (total > MAX_AMOUNT) return null
                sawAmount = true
            }
        }
        return TokenInfo(
            version = "A",
            amount = if (sawAmount) total else null,
            unit = sanitizedUnit(obj.get("unit")?.takeIf { it.isJsonPrimitive }?.asString),
            mintHost = mintHost,
            memo = sanitizedMemo(obj.get("memo")?.takeIf { it.isJsonPrimitive }?.asString),
            complete = complete
        )
    }

    // MARK: - V4 (CBOR)

    /**
     * Minimal walk of the NUT-00 TokenV4 CBOR map:
     * { "m": mint, "u": unit, "d": memo, "t": [ { "i": bytes, "p": [ { "a": amount, … } ] } ] }
     */
    private fun decodeV4(payload: ByteArray): TokenInfo? {
        val reader = CBORReader(payload)
        val root = reader.parseValue(0) as? CBORReader.CborMap ?: return null
        var mintHost: String? = null
        var unit: String? = null
        var memo: String? = null
        var total = 0L
        var sawAmount = false
        for ((key, value) in root.pairs) {
            val name = (key as? CBORReader.CborText)?.s ?: continue
            when {
                name == "m" && value is CBORReader.CborText -> mintHost = sanitizedHost(value.s)
                name == "u" && value is CBORReader.CborText -> unit = sanitizedUnit(value.s)
                name == "d" && value is CBORReader.CborText -> memo = sanitizedMemo(value.s)
                name == "t" && value is CBORReader.CborArray -> {
                    for (group in value.items.filterIsInstance<CBORReader.CborMap>()) {
                        for ((gKey, gVal) in group.pairs) {
                            if ((gKey as? CBORReader.CborText)?.s != "p") continue
                            val proofs = gVal as? CBORReader.CborArray ?: continue
                            for (proof in proofs.items.filterIsInstance<CBORReader.CborMap>()) {
                                for ((pKey, pVal) in proof.pairs) {
                                    if ((pKey as? CBORReader.CborText)?.s != "a") continue
                                    val amount = (pVal as? CBORReader.CborUnsigned)?.v ?: continue
                                    if (amount == 0uL || amount > MAX_AMOUNT.toULong()) continue
                                    total += amount.toLong()
                                    if (total > MAX_AMOUNT) return null
                                    sawAmount = true
                                }
                            }
                        }
                    }
                }
            }
        }
        return TokenInfo(
            version = "B",
            amount = if (sawAmount) total else null,
            unit = unit,
            mintHost = mintHost,
            memo = memo
        )
    }

    // MARK: - Display sanitization (values are attacker-controlled)

    private fun sanitizedHost(mint: String): String? {
        if (mint.length > 512) return null
        val host = try { URI(mint).host } catch (e: Exception) { null } ?: return null
        if (host.isEmpty()) return null
        return host.lowercase().take(48)
    }

    private fun sanitizedUnit(unit: String?): String? {
        if (unit.isNullOrEmpty() || unit.length > 12) return null
        if (!unit.all { it.isLetterOrDigit() }) return null
        return unit
    }

    private fun sanitizedMemo(memo: String?): String? {
        if (memo.isNullOrEmpty() || memo.length > 512) return null
        val cleaned = memo.filter { !it.isISOControl() }.trim()
        if (cleaned.isEmpty()) return null
        return cleaned.take(80)
    }

    // MARK: - Minimal CBOR reader

    /**
     * Just enough definite-length CBOR to traverse a TokenV4 map. Bounded in
     * depth, item count, and byte length; indefinite-length items and anything
     * else exotic make the parse fail (the caller degrades to a generic chip).
     */
    private class CBORReader(private val data: ByteArray) {
        sealed interface CborValue
        data class CborUnsigned(val v: ULong) : CborValue
        data class CborText(val s: String) : CborValue
        data class CborArray(val items: List<CborValue>) : CborValue
        data class CborMap(val pairs: List<Pair<CborValue, CborValue>>) : CborValue
        /** Parsed-and-skipped content we don't need (byte strings, negatives, floats…) */
        object CborOpaque : CborValue

        private var pos = 0
        private var itemCount = 0

        companion object {
            private const val MAX_DEPTH = 16
            private const val MAX_ITEMS = 4_096
            private const val MAX_CONTAINER_SIZE = 1_024L
        }

        fun parseValue(depth: Int): CborValue? {
            if (depth > MAX_DEPTH) return null
            if (++itemCount > MAX_ITEMS) return null
            if (pos >= data.size) return null
            val initial = data[pos++].toInt() and 0xFF
            val major = initial shr 5
            val info = initial and 0x1F
            val arg = readArgument(info) ?: return null // indefinite-length (31) and reserved fail here
            return when (major) {
                0 -> CborUnsigned(arg)
                1 -> CborOpaque // negative int, not needed
                2 -> if (skipBytes(arg)) CborOpaque else null
                3 -> readText(arg)
                4 -> readArray(arg, depth)
                5 -> readMap(arg, depth)
                6 -> { // tag: parse the tagged value, then discard it
                    parseValue(depth + 1) ?: return null
                    CborOpaque
                }
                7 -> CborOpaque // simple/float — argument bytes already consumed by readArgument
                else -> null
            }
        }

        private fun readArgument(info: Int): ULong? {
            return when {
                info < 24 -> info.toULong()
                info == 24 -> readUInt(1)
                info == 25 -> readUInt(2)
                info == 26 -> readUInt(4)
                info == 27 -> readUInt(8)
                else -> null // 28-30 reserved, 31 indefinite-length
            }
        }

        private fun readUInt(n: Int): ULong? {
            if (pos + n > data.size) return null
            var v = 0uL
            repeat(n) {
                v = (v shl 8) or (data[pos++].toInt() and 0xFF).toULong()
            }
            return v
        }

        private fun skipBytes(n: ULong): Boolean {
            if (n > (data.size - pos).toULong()) return false
            pos += n.toInt()
            return true
        }

        private fun readText(n: ULong): CborValue? {
            if (n > (data.size - pos).toULong()) return null
            val len = n.toInt()
            val s = String(data, pos, len, Charsets.UTF_8)
            pos += len
            return CborText(s)
        }

        private fun readArray(n: ULong, depth: Int): CborValue? {
            if (n > MAX_CONTAINER_SIZE.toULong()) return null
            val items = ArrayList<CborValue>(n.toInt())
            repeat(n.toInt()) {
                items.add(parseValue(depth + 1) ?: return null)
            }
            return CborArray(items)
        }

        private fun readMap(n: ULong, depth: Int): CborValue? {
            if (n > MAX_CONTAINER_SIZE.toULong()) return null
            val pairs = ArrayList<Pair<CborValue, CborValue>>(n.toInt())
            repeat(n.toInt()) {
                val k = parseValue(depth + 1) ?: return null
                val v = parseValue(depth + 1) ?: return null
                pairs.add(k to v)
            }
            return CborMap(pairs)
        }
    }
}
