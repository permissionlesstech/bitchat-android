package com.bitchat.android.util

import java.net.URI
import java.net.URLDecoder

/**
 * Detects and strips common URL tracking query parameters (e.g. `igsh`, `utm_*`,
 * `fbclid`, `gclid`, `si`) so users can be warned or share clean links (issue #731).
 */
object UrlTrackerSanitizer {

    data class InspectionResult(
        val originalUrl: String,
        val sanitizedUrl: String,
        val removedTrackers: List<String>
    ) {
        val hasTrackers: Boolean get() = removedTrackers.isNotEmpty()
    }

    private val exactTrackerKeys: Set<String> = setOf(
        "igsh",
        "igshid",
        "fbclid",
        "gclid",
        "dclid",
        "gbraid",
        "wbraid",
        "msclkid",
        "twclid",
        "ttclid",
        "yclid",
        "mc_cid",
        "mc_eid",
        "si",
        "spm",
        "ref_src",
        "ref_url",
        "_hsenc",
        "_hsmi",
        "mkt_tok"
    )

    private val trackerPrefixes: List<String> = listOf(
        "utm_",
        "hsa_",
        "oly_",
        "vero_"
    )

    fun isTrackingParameter(name: String): Boolean {
        val lower = name.lowercase()
        if (lower in exactTrackerKeys) return true
        return trackerPrefixes.any { lower.startsWith(it) }
    }

    fun inspect(rawUrl: String): InspectionResult {
        val trimmed = rawUrl.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull()
            ?: return InspectionResult(trimmed, trimmed, emptyList())
        val rawQuery = uri.rawQuery
            ?: return InspectionResult(trimmed, trimmed, emptyList())
        if (rawQuery.isEmpty()) {
            return InspectionResult(trimmed, trimmed, emptyList())
        }

        val kept = mutableListOf<String>()
        val removed = mutableListOf<String>()
        for (pair in rawQuery.split("&")) {
            if (pair.isEmpty()) continue
            val rawKey = pair.substringBefore("=")
            val key = decodeQueryKey(rawKey)
            if (isTrackingParameter(key)) {
                removed.add(key)
            } else {
                kept.add(pair)
            }
        }

        if (removed.isEmpty()) {
            return InspectionResult(trimmed, trimmed, emptyList())
        }

        val base = trimmed.substringBefore("?")
        val fragment = trimmed.substringAfter("#", missingDelimiterValue = "")
        val queryPart = if (kept.isEmpty()) "" else "?" + kept.joinToString("&")
        val fragmentPart = if (fragment.isEmpty()) "" else "#$fragment"
        val sanitized = "$base$queryPart$fragmentPart"

        return InspectionResult(
            originalUrl = trimmed,
            sanitizedUrl = sanitized,
            removedTrackers = removed
        )
    }

    private fun decodeQueryKey(key: String): String =
        runCatching { URLDecoder.decode(key, "UTF-8") }.getOrDefault(key)
}
