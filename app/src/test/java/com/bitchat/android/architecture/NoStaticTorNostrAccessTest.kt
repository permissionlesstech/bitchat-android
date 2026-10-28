package com.bitchat.android.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Outside `:core:tor` and `:core:nostr`, nothing reaches the Tor and Nostr singletons through a
 * static accessor: callers take them by injection. Read from the sources, comments dropped.
 */
class NoStaticTorNostrAccessTest {

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val accessors = listOf(
        Regex("""\b(ArtiTorManager|NostrRelayManager|NostrClient|NostrTransport|NostrEventDeduplicator|LocationChannelManager|LocationNotesManager|GeohashBookmarksStore)\.(getInstance|shared)\b"""),
        Regex("""NearbyNotesController\.shared"""),
        Regex("""\b(OkHttpProvider|NostrNetwork|RelayDirectory|LiveLocationPrivacyGate|PoWPreferenceManager|NostrBackgroundRuntime|LocationNotesInitializer)\.[a-z]\w*\("""),
    )

    private fun sourceRoots(): List<File> {
        val modules = listOf("app", "core/chat", "wear").map { File(root, it) } +
            File(root, "feature").walkTopDown()
                .onEnter { it.name != "build" && it.name != "src" }
                .filter { File(it, "build.gradle.kts").isFile }
        return modules.map { File(it, "src/main") }.filter { it.isDirectory }
    }

    @Test
    fun noStaticAccessOutsideTorAndNostr() {
        val offenders = sourceRoots().flatMap { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val code = line.substringBefore("//").trim()
                    val commentLine = code.startsWith("*") || code.startsWith("/*")
                    if (!commentLine && accessors.any { it.containsMatchIn(code) }) {
                        "${file.relativeTo(root).path}:${index + 1}"
                    } else null
                }
            }.toList()
        }.distinct()
        assertTrue(
            "Tor and Nostr singletons must be injected, not reached statically:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }
}
