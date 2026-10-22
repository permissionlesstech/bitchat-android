package com.bitchat.android.ui

import com.google.gson.JsonParser
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.math.BigDecimal
import java.util.Base64
import java.util.Currency
import java.util.Locale

/**
 * Bounded, display-only Cashu token decoder. Tokens are bearer instruments, so
 * this class never contacts a mint or attempts to hold/redeem funds.
 */
object CashuTokenDecoder {
    const val MAX_TOKEN_LENGTH = 60_000
    private const val MAX_AMOUNT = 2_100_000_000_000_000L

    data class TokenInfo(
        val version: Char,
        val amount: Long?,
        val unit: String?,
        val mintHost: String?,
        val memo: String?
    ) {
        val displayAmount: String?
            get() = amount?.let { value ->
                val displayUnit = unit ?: "sat"
                val minorDigits = minorUnitDigits(displayUnit)
                val formatted = if (minorDigits == null || minorDigits == 0) {
                    value.toString()
                } else {
                    BigDecimal.valueOf(value, minorDigits).setScale(minorDigits).toPlainString()
                }
                "$formatted $displayUnit"
            }
    }

    fun bareToken(raw: String): String? {
        var token = raw.trim()
        if ('%' in token) token = percentDecode(token) ?: return null
        token = when {
            token.startsWith("cashu://", ignoreCase = true) -> token.substring(8)
            token.startsWith("cashu:", ignoreCase = true) -> token.substring(6)
            else -> token
        }
        if (token.length !in 12..MAX_TOKEN_LENGTH) return null
        if (!token.startsWith("cashuA") && !token.startsWith("cashuB")) return null
        if (token.any { !it.isLetterOrDigit() && it !in "-_+/=" }) return null
        return token
    }

    /**
     * Permissive decoding is suitable for display: unsupported but plausible
     * v4 CBOR still gets a generic chip. Strict decoding is required before
     * sending and accepts only a fully parsed token with a positive amount.
     */
    fun decode(raw: String, strict: Boolean = false): TokenInfo? {
        val token = bareToken(raw) ?: return null
        val payload = decodeBase64Url(token.substring(6)) ?: return null
        if (payload.isEmpty()) return null
        val info = when (token[5]) {
            'A' -> decodeV3(payload)
            'B' -> decodeV4(payload) ?: if (strict) null else TokenInfo('B', null, null, null, null)
            else -> null
        } ?: return null
        return if (!strict || (info.amount != null && info.amount > 0)) info else null
    }

    fun extractTokens(text: String, max: Int = 3): List<String> {
        if (text.isEmpty() || max <= 0) return emptyList()
        val matches = TOKEN_REGEX.findAll(text)
        val result = LinkedHashSet<String>()
        for (match in matches) {
            bareToken(match.value)?.let(result::add)
            if (result.size == max) break
        }
        return result.toList()
    }

    fun walletUri(token: String): String? = bareToken(token)?.let { "cashu:${encodeUriComponent(it)}" }

    fun webRedeemUri(token: String): String? =
        bareToken(token)?.let { "https://redeem.cashu.me/?token=${encodeUriComponent(it)}" }

    private fun encodeUriComponent(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    /** Percent-decodes URI input without URLDecoder's form-specific '+' → space conversion. */
    private fun percentDecode(value: String): String? {
        val output = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            if (value[index] != '%') {
                output.append(value[index++])
                continue
            }
            val bytes = ArrayList<Byte>()
            while (index < value.length && value[index] == '%') {
                if (index + 2 >= value.length) return null
                val byte = value.substring(index + 1, index + 3).toIntOrNull(16) ?: return null
                bytes += byte.toByte()
                index += 3
            }
            output.append(String(bytes.toByteArray(), StandardCharsets.UTF_8))
        }
        return output.toString()
    }

    private fun decodeBase64Url(input: String): ByteArray? {
        val normalized = input.replace('-', '+').replace('_', '/').trimEnd('=')
        if (normalized.length % 4 == 1) return null
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return runCatching { Base64.getDecoder().decode(padded) }.getOrNull()
    }

    private fun decodeV3(payload: ByteArray): TokenInfo? = runCatching {
        val root = JsonParser.parseString(String(payload, StandardCharsets.UTF_8)).asJsonObject
        val entries = root.getAsJsonArray("token")?.takeIf { it.size() > 0 } ?: return null
        var total = 0L
        var sawAmount = false
        var mintHost: String? = null
        for (entryElement in entries) {
            val entry = entryElement.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            if (mintHost == null) mintHost = sanitizeHost(entry.get("mint")?.takeIf { it.isJsonPrimitive }?.asString)
            val proofs = entry.getAsJsonArray("proofs") ?: continue
            for (proofElement in proofs) {
                val amountElement = proofElement.takeIf { it.isJsonObject }?.asJsonObject?.get("amount") ?: continue
                if (!amountElement.isJsonPrimitive || !amountElement.asJsonPrimitive.isNumber) continue
                val value = runCatching { amountElement.asBigDecimal.longValueExact() }.getOrNull() ?: continue
                if (value <= 0 || value > MAX_AMOUNT) continue
                if (total > MAX_AMOUNT - value) return null
                total += value
                sawAmount = true
            }
        }
        TokenInfo(
            version = 'A',
            amount = total.takeIf { sawAmount },
            unit = sanitizeUnit(root.get("unit")?.takeIf { it.isJsonPrimitive }?.asString),
            mintHost = mintHost,
            memo = sanitizeMemo(root.get("memo")?.takeIf { it.isJsonPrimitive }?.asString)
        )
    }.getOrNull()

    private fun decodeV4(payload: ByteArray): TokenInfo? {
        val root = CborReader(payload).parseComplete() as? CborValue.MapValue ?: return null
        var total = 0L
        var sawAmount = false
        var mintHost: String? = null
        var unit: String? = null
        var memo: String? = null
        for ((key, value) in root.pairs) {
            when ((key as? CborValue.Text)?.value) {
                "m" -> mintHost = sanitizeHost((value as? CborValue.Text)?.value)
                "u" -> unit = sanitizeUnit((value as? CborValue.Text)?.value)
                "d" -> memo = sanitizeMemo((value as? CborValue.Text)?.value)
                "t" -> for (group in (value as? CborValue.ArrayValue)?.values.orEmpty()) {
                    for ((groupKey, groupValue) in (group as? CborValue.MapValue)?.pairs.orEmpty()) {
                        if ((groupKey as? CborValue.Text)?.value != "p") continue
                        for (proof in (groupValue as? CborValue.ArrayValue)?.values.orEmpty()) {
                            for ((proofKey, proofValue) in (proof as? CborValue.MapValue)?.pairs.orEmpty()) {
                                if ((proofKey as? CborValue.Text)?.value != "a") continue
                                val amount = (proofValue as? CborValue.Unsigned)?.value ?: continue
                                if (amount == 0L || amount > MAX_AMOUNT) continue
                                if (total > MAX_AMOUNT - amount) return null
                                total += amount
                                sawAmount = true
                            }
                        }
                    }
                }
            }
        }
        return TokenInfo('B', total.takeIf { sawAmount }, unit, mintHost, memo)
    }

    private fun sanitizeHost(value: String?): String? = value
        ?.takeIf { it.length <= 512 }
        ?.let { runCatching { URI(it).host }.getOrNull() }
        ?.takeIf { it.isNotEmpty() }
        ?.lowercase()
        ?.take(48)

    private fun sanitizeUnit(value: String?): String? =
        value?.takeIf { it.isNotEmpty() && it.length <= 12 && it.all(Char::isLetterOrDigit) }

    private fun sanitizeMemo(value: String?): String? {
        if (value == null || value.length > 512) return null
        return value.filterNot(Char::isISOControl).trim().take(80).takeIf(String::isNotEmpty)
    }

    /** ISO-4217 values use their currency's minor unit; custom units stay integer-denominated. */
    private fun minorUnitDigits(unit: String): Int? {
        return runCatching {
            Currency.getInstance(unit.uppercase(Locale.ROOT)).defaultFractionDigits
        }.getOrNull()?.takeIf { it >= 0 }
    }

    private val TOKEN_REGEX = Regex("""(?i:cashu:(?://)?)?cashu[AB][A-Za-z0-9_+/%=-]{6,}""")
}

private sealed interface CborValue {
    data class Unsigned(val value: Long) : CborValue
    data class Text(val value: String) : CborValue
    data class ArrayValue(val values: List<CborValue>) : CborValue
    data class MapValue(val pairs: List<Pair<CborValue, CborValue>>) : CborValue
    data object Opaque : CborValue
}

private class CborReader(private val bytes: ByteArray) {
    private var index = 0
    private var itemBudget = 50_000

    fun parseComplete(): CborValue? {
        val value = parseValue(0) ?: return null
        return value.takeIf { index == bytes.size }
    }

    private fun parseValue(depth: Int): CborValue? {
        if (depth >= 16 || itemBudget-- <= 0) return null
        val (major, argument) = readHead() ?: return null
        return when (major) {
            0 -> CborValue.Unsigned(argument.takeIf { it <= Long.MAX_VALUE }?.toLong() ?: return null)
            1 -> CborValue.Opaque
            2 -> if (readBytes(argument) != null) CborValue.Opaque else null
            3 -> readBytes(argument)?.toString(StandardCharsets.UTF_8)?.let(CborValue::Text)
            4 -> parseContainer(argument, depth) { CborValue.ArrayValue(it) }
            5 -> {
                if (argument > 10_000 || argument > itemBudget / 2) return null
                val pairs = ArrayList<Pair<CborValue, CborValue>>(argument.coerceAtMost(64).toInt())
                repeat(argument.toInt()) {
                    pairs += (parseValue(depth + 1) ?: return null) to (parseValue(depth + 1) ?: return null)
                }
                CborValue.MapValue(pairs)
            }
            6 -> parseValue(depth + 1)
            7 -> CborValue.Opaque
            else -> null
        }
    }

    private fun parseContainer(
        count: Long,
        depth: Int,
        wrap: (List<CborValue>) -> CborValue
    ): CborValue? {
        if (count > 10_000 || count > itemBudget) return null
        val values = ArrayList<CborValue>(count.coerceAtMost(64).toInt())
        repeat(count.toInt()) { values += parseValue(depth + 1) ?: return null }
        return wrap(values)
    }

    private fun readHead(): Pair<Int, Long>? {
        if (index >= bytes.size) return null
        val head = bytes[index++].toInt() and 0xff
        val major = head ushr 5
        val info = head and 0x1f
        val argument = when (info) {
            in 0..23 -> info.toLong()
            24 -> readUInt(1)
            25 -> readUInt(2)
            26 -> readUInt(4)
            27 -> readUInt(8)
            else -> null
        } ?: return null
        return major to argument
    }

    private fun readUInt(width: Int): Long? {
        if (bytes.size - index < width) return null
        var value = 0L
        repeat(width) {
            val next = bytes[index++].toLong() and 0xff
            if (value > (Long.MAX_VALUE - next) ushr 8) return null
            value = (value shl 8) or next
        }
        return value
    }

    private fun readBytes(count: Long): ByteArray? {
        if (count < 0 || count > bytes.size - index) return null
        val end = index + count.toInt()
        return bytes.copyOfRange(index, end).also { index = end }
    }
}
