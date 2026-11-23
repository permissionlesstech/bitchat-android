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

    private val ltrTokenRegex = Regex("""(@[A-Za-z0-9_.-]+|#[A-Za-z0-9_.-]+|https?://\S+)""")

    /** Wraps a single LTR token in Unicode LRI/PDI isolates. */
    fun isolateLtr(token: String): String {
        if (token.isEmpty()) return token
        return "$LRI$token$PDI"
    }

    /**
     * Isolates `@mentions`, `#channels`/`#geohashes`, and URLs within mixed
     * RTL/LTR chat text so bidirectional reordering preserves token order.
     */
    fun isolateMixedChatTokens(text: String): String {
        if (text.isEmpty()) return text
        return ltrTokenRegex.replace(text) { match -> isolateLtr(match.value) }
    }
}
