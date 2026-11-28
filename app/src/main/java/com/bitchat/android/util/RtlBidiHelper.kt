package com.bitchat.android.util

/**
 * Unicode bidirectional (BiDi) formatting helpers for RTL locales (issues #679, #693).
 * Wraps LTR tokens such as `@nicknames`, `#geohashes`, and URLs in Unicode
 * Left-to-Right Isolate (`U+2066` ... `U+2069`) so they render cleanly inside
 * Arabic, Hebrew, or Persian chat messages without scrambling punctuation.
 */
object RtlBidiHelper {

    private const val LRI: Char = '\u2066'
    private const val PDI: Char = '\u2069'

    // Optional `#abcd` stays inside the same isolate as the mention (ChatScreen suffixes).
    private val mentionRegex = Regex("""(?<![\p{L}0-9._%+\-])@[\p{L}0-9_]+(?:#[a-fA-F0-9]{4})?""")
    private val hashRegex = Regex("""(?<![\p{L}0-9_])#[A-Za-z0-9_]+""")
    private val urlRegex = Regex("""https?://\S+""")
    private val urlTrailingPunctuation = ".,;:!?)]}'\""

    /** Wraps a single LTR token in Unicode LRI/PDI isolates. */
    fun isolateLtr(token: String): String {
        if (token.isEmpty()) return token
        return "$LRI$token$PDI"
    }

    /**
     * Isolates `@mentions`, `#channels`/`#geohashes`, and URLs within mixed
     * RTL/LTR chat text so bidirectional reordering preserves token order.
     * Email domains are left alone, and URL wrapping punctuation stays outside the isolate.
     */
    fun isolateMixedChatTokens(text: String): String {
        if (text.isEmpty()) return text
        val spans = ArrayList<IntRange>()
        fun add(range: IntRange) {
            if (spans.any { it.first <= range.last && range.first <= it.last }) return
            spans.add(range)
        }
        mentionRegex.findAll(text).forEach { add(it.range) }
        hashRegex.findAll(text).forEach { add(it.range) }
        urlRegex.findAll(text).forEach { match ->
            var end = match.range.last
            while (end >= match.range.first && text[end] in urlTrailingPunctuation) end--
            if (end >= match.range.first) add(match.range.first..end)
        }
        if (spans.isEmpty()) return text
        spans.sortBy { it.first }
        val out = StringBuilder(text.length + spans.size * 2)
        var cursor = 0
        for (span in spans) {
            if (span.first < cursor) continue
            out.append(text, cursor, span.first)
            out.append(LRI)
            out.append(text, span.first, span.last + 1)
            out.append(PDI)
            cursor = span.last + 1
        }
        out.append(text, cursor, text.length)
        return out.toString()
    }
}
