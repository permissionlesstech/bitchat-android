package com.bitchat.android.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nothing reaches the Tor and Nostr singletons through a static accessor: callers take them by
 * injection. Outside `:core:tor` and `:core:nostr` there are no exceptions; inside `:core:nostr`
 * only the bridge module, a class's own companion and the classes still built statically may. Read from the sources, comments dropped.
 */
class NoStaticTorNostrAccessTest {

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    // Group 1 is the class reached, so a class's own file can be told apart from its callers.
    private val accessors = listOf(
        Regex("""\b(ArtiTorManager|NostrRelayManager|NostrClient|NostrTransport|NostrEventDeduplicator|LocationChannelManager|LocationNotesManager|GeohashBookmarksStore)\.(getInstance|shared)\b"""),
        Regex("""\b(NearbyNotesController)\.shared"""),
        Regex("""\b(OkHttpProvider|NostrNetwork|RelayDirectory|LiveLocationPrivacyGate|PoWPreferenceManager|NostrBackgroundRuntime|LocationNotesInitializer)\.[a-z]\w*\("""),
    )

    private fun sourceRoots(): List<File> {
        val modules = listOf("app", "core/chat", "wear").map { File(root, it) } +
            File(root, "feature").walkTopDown()
                .onEnter { it.name != "build" && it.name != "src" }
                .filter { File(it, "build.gradle.kts").isFile }
        return modules.map { File(it, "src/main") }.filter { it.isDirectory }
    }

    /** `path:line` of every static reach in [files]; [allowed] may waive one class in one file. */
    private fun offenders(
        files: List<File>,
        allowed: (file: File, reachedClass: String) -> Boolean = { _, _ -> false }
    ): List<String> = files.flatMap { file ->
        file.readLines().mapIndexedNotNull { index, line ->
            val code = line.substringBefore("//").trim()
            val commentLine = code.startsWith("*") || code.startsWith("/*")
            val reached = if (commentLine) emptyList() else accessors.flatMap { regex ->
                regex.findAll(code).map { it.groupValues[1] }.toList()
            }
            if (reached.any { !allowed(file, it) }) "${file.relativeTo(root).path}:${index + 1}" else null
        }
    }.distinct()

    private fun kotlinFiles(dirs: List<File>): List<File> =
        dirs.flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    @Test
    fun noStaticAccessOutsideTorAndNostr() {
        val files = kotlinFiles(sourceRoots())
        assertTrue("Scanned no Kotlin sources; the source roots moved", files.isNotEmpty())
        val offenders = offenders(files)
        assertTrue(
            "Tor and Nostr singletons must be injected, not reached statically:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    /**
     * Still built statically and bridged by `NostrBridgeModule`, so their own files cannot take
     * dependencies by constructor yet. Task 5 converts them and empties this list.
     */
    private val stillStaticInNostr = setOf(
        "NostrNetwork", "RelayDirectory", "LiveLocationPrivacyGate", "PoWPreferenceManager",
        "NostrEventDeduplicator", "NostrRelayManager", "NostrClient", "NostrTransport",
        "LocationChannelManager", "GeohashBookmarksStore", "LocationNotesManager",
        "NearbyNotesController",
    )

    @Test
    fun noStaticAccessInsideNostrExceptBridges() {
        val files = kotlinFiles(listOf(File(root, "core/nostr/src/main")))
            .filter { it.nameWithoutExtension !in stillStaticInNostr }
        assertTrue("Scanned no Kotlin sources; the source roots moved", files.isNotEmpty())
        val offenders = offenders(files) { file, reachedClass ->
            file.invariantSeparatorsPath.endsWith("nostr/di/NostrBridgeModule.kt") ||
                file.nameWithoutExtension == reachedClass
        }
        assertTrue(
            "Inside :core:nostr only the bridge module reaches the singletons statically:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }
}
