package com.bitchat.android.util

import java.net.URLDecoder
import java.util.Locale

object TrackingUrlDetector {
    private val exactTrackingParameters = setOf(
        "dclid",
        "fbclid",
        "gclid",
        "igsh",
        "msclkid",
        "ttclid",
        "twclid",
    )

    private val urlPattern = Regex(
        pattern = """(?<![\w@.-])(?:https?://|www\.)[^\s<>"']+|(?<![\w@.-])(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,}(?::\d+)?(?:[/?#][^\s<>"']*)?""",
        option = RegexOption.IGNORE_CASE,
    )

    fun containsTrackingUrl(text: String): Boolean =
        urlPattern.findAll(text).any { match ->
            hasTrackingParameter(match.value.trimUrlPunctuation())
        }

    fun hasTrackingParameter(url: String): Boolean {
        val queryStart = url.indexOf('?')
        if (queryStart < 0) return false
        val firstFragment = url.indexOf('#')
        if (firstFragment in 0 until queryStart) return false

        val fragmentStart = url.indexOf('#', startIndex = queryStart + 1)
            .let { if (it >= 0) it else url.length }
        val query = url.substring(queryStart + 1, fragmentStart)

        return query.split(querySeparator).any { parameter ->
            val encodedName = parameter.substringBefore('=')
            val name = runCatching {
                URLDecoder.decode(encodedName, Charsets.UTF_8.name())
            }.getOrNull()?.lowercase(Locale.ROOT) ?: return@any false

            name.startsWith("utm_") || name in exactTrackingParameters
        }
    }

    private fun String.trimUrlPunctuation(): String =
        trimEnd('.', ',', ';', ':', '!', '"', '\'', ')', ']', '}')

    private val querySeparator = Regex("&(?:amp;)?", RegexOption.IGNORE_CASE)
}
