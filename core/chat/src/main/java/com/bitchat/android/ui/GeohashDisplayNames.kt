package com.bitchat.android.ui

import java.util.Locale

/**
 * Names that require a short identity suffix, calculated across both people sections.
 *
 * Matching is case-insensitive to mirror geohash chat's nickname collision handling.
 */
fun duplicateGeohashBaseNames(people: List<GeoPerson>): Set<String> =
    people
        .groupingBy { splitSuffix(it.displayName).first.lowercase(Locale.ROOT) }
        .eachCount()
        .filterValues { it > 1 }
        .keys

/**
 * The same `#abcd` disambiguator used by geohash chat.
 *
 * Presence rows normally carry only a base nickname, so derive the suffix from the full Nostr
 * public key when a collision exists. Preserve an already-announced suffix for compatibility.
 */
fun geohashIdentitySuffix(person: GeoPerson, showHashSuffix: Boolean): String {
    if (!showHashSuffix) return ""
    val announcedSuffix = splitSuffix(person.displayName).second
    return announcedSuffix.ifEmpty { "#${person.id.takeLast(4)}" }
}

fun disambiguatedGeohashDisplayName(
    person: GeoPerson,
    duplicateBaseNames: Set<String>,
): String {
    val baseName = splitSuffix(person.displayName).first
    val showSuffix = baseName.lowercase(Locale.ROOT) in duplicateBaseNames
    return baseName + geohashIdentitySuffix(person, showSuffix)
}
