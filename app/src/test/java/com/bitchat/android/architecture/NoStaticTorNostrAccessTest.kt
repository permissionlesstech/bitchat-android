package com.bitchat.android.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Tor and Nostr singletons are built by Hilt, so nothing reaches one through its class name and
 * none keeps a static holder. Read from every module's main sources with comments and string text
 * dropped; there are no exemptions, not even a class's own file.
 */
class NoStaticTorNostrAccessTest {

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    /** The singletons Hilt builds, each declared in a file of its own name. */
    private val injected = listOf(
        "ArtiTorManager", "OkHttpProvider", "NostrRelayManager", "NostrClient", "NostrTransport",
        "NostrEventDeduplicator", "LocationChannelManager", "LocationNotesManager",
        "GeohashBookmarksStore", "NearbyNotesController", "RelayDirectory", "LiveLocationPrivacyGate",
        "PoWPreferenceManager", "NostrBackgroundRuntime", "LocationNotesInitializer",
    )

    /**
     * Pure companion functions that may still be called through the class name. Beyond these, a
     * `Name.` may only be followed by a capitalised member: a nested type or a constant
     * (`LocationChannelManager.PermissionState`, `NostrRelayManager.OWNER_BACKGROUND`).
     */
    private val companionFunctions = setOf(
        "NostrRelayManager.defaultRelays",
        "GeohashBookmarksStore.normalize",
    )

    private val reach = Regex(
        """(?<![\w@])(${injected.joinToString("|")})\s*(\.|::)\s*([A-Za-z_]\w*)"""
    )

    @Test
    fun noSingletonIsReachedThroughItsClassName() {
        val offenders = mainSources().flatMap { (file, code) ->
            reach.findAll(code).filterNot { match ->
                val (name, separator, member) = match.destructured
                (separator == "::" && member == "class") ||
                    (separator == "." && member.first().isUpperCase() && member != "Companion") ||
                    (separator == "." && "$name.$member" in companionFunctions)
            }.map { "${location(file, code, it.range.first)}: ${it.value}" }.toList()
        }
        assertTrue(
            "Tor and Nostr singletons must be injected, not reached statically:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test
    fun nostrNetworkIsGone() {
        val offenders = mainSources().flatMap { (file, code) ->
            Regex("""\bNostrNetwork\b""").findAll(code).map { location(file, code, it.range.first) }.toList()
        }
        assertTrue(
            "NostrHttpClients is a binding now; nothing installs or asks NostrNetwork:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    /**
     * Each singleton is a class with an `@Inject` constructor and no static holder. The one
     * exception to `@Inject` is [NearbyNotesController], which keeps its lambda constructor for
     * tests and is built by `NostrModule`.
     */
    @Test
    fun noSingletonKeepsAStaticHolder() {
        val sources = mainSources(listOf("core/tor", "core/nostr")).associate { (file, code) ->
            file.nameWithoutExtension to code
        }
        val holder = Regex("""fun\s+getInstance\s*\(|\bval\s+shared\b|\bINSTANCE\b""")
        val problems = injected.flatMap { name ->
            val code = sources[name] ?: return@flatMap listOf("$name: no $name.kt in core/tor or core/nostr")
            listOfNotNull(
                holder.find(code)?.let { "$name: declares a static holder (${it.value})" },
                Regex("""\bobject\s+$name\b""").find(code)?.let { "$name: is an object" },
                if (name != "NearbyNotesController" &&
                    !Regex("""@Inject\s+constructor""").containsMatchIn(code)
                ) "$name: has no @Inject constructor" else null,
            )
        }
        assertTrue("Static singleton holders remain:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    /** Every Kotlin file under `<module>/src/main` below [trees], paired with its code. */
    private fun mainSources(trees: List<String> = listOf("app", "wear", "core", "feature")): List<Pair<File, String>> {
        val files = trees.map { File(root, it) }.flatMap { tree ->
            tree.walkTopDown()
                .onEnter { it.name != "build" }
                .filter { it.isFile && it.extension == "kt" && "/src/main/" in it.invariantSeparatorsPath }
                .toList()
        }
        assertTrue("Scanned no Kotlin sources; the source roots moved", files.isNotEmpty())
        return files.map { it to codeOnly(it.readText()) }
    }

    private fun location(file: File, code: String, offset: Int): String =
        "${file.relativeTo(root).invariantSeparatorsPath}:${code.substring(0, offset).count { it == '\n' } + 1}"

    /**
     * [text] with comments and string literal text replaced by spaces, so a KDoc link or a log
     * message is not mistaken for code. Code inside `${...}` templates is kept, and so are line
     * breaks, so offsets still map to lines.
     */
    private fun codeOnly(text: String): String {
        val out = StringBuilder(text.length)
        // What the cursor is inside, innermost last: 's' string, 'r' raw string, 't' template code.
        val frames = ArrayDeque<Char>()
        val templateBraces = ArrayDeque<Int>()
        var i = 0
        fun blank(n: Int) = repeat(n) {
            if (i < text.length) out.append(if (text[i] == '\n') '\n' else ' ')
            i++
        }
        while (i < text.length) {
            val c = text[i]
            when (frames.lastOrNull()) {
                's' -> when {
                    c == '\\' -> blank(2)
                    c == '"' -> { frames.removeLast(); blank(1) }
                    text.startsWith("\${", i) -> { frames.addLast('t'); templateBraces.addLast(0); blank(2) }
                    else -> blank(1)
                }
                'r' -> when {
                    text.startsWith("\"\"\"", i) -> { frames.removeLast(); blank(3) }
                    text.startsWith("\${", i) -> { frames.addLast('t'); templateBraces.addLast(0); blank(2) }
                    else -> blank(1)
                }
                else -> when {
                    text.startsWith("//", i) -> while (i < text.length && text[i] != '\n') blank(1)
                    text.startsWith("/*", i) -> {
                        var depth = 0
                        do {
                            when {
                                text.startsWith("/*", i) -> { depth++; blank(2) }
                                text.startsWith("*/", i) -> { depth--; blank(2) }
                                else -> blank(1)
                            }
                        } while (depth > 0 && i < text.length)
                    }
                    text.startsWith("\"\"\"", i) -> { frames.addLast('r'); blank(3) }
                    c == '"' -> { frames.addLast('s'); blank(1) }
                    c == '\'' -> {
                        val end = text.indexOf('\'', if (text.getOrNull(i + 1) == '\\') i + 3 else i + 2)
                        blank(if (end < 0) 1 else end - i + 1)
                    }
                    frames.lastOrNull() == 't' && c == '{' -> {
                        templateBraces.addLast(templateBraces.removeLast() + 1); out.append(c); i++
                    }
                    frames.lastOrNull() == 't' && c == '}' -> {
                        val depth = templateBraces.removeLast()
                        if (depth == 0) { frames.removeLast(); blank(1) }
                        else { templateBraces.addLast(depth - 1); out.append(c); i++ }
                    }
                    else -> { out.append(c); i++ }
                }
            }
        }
        return out.toString()
    }
}
