package com.bitchat.android.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mesh singletons are built by Hilt (or, while a task of 10c-2a-1 is still pending, bridged
 * into the graph), so callers take them by injection and never reach one through its class name.
 * Read from the sources of the modules that use them, comments and string text dropped.
 *
 * Callers inside `:core:mesh` build the classes themselves and are not scanned. `app/` and `wear/`
 * are exempt as trees until Task 3 moves them.
 */
class NoStaticMeshAccessTest {

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    /** Classes with a companion accessor. */
    private val accessorClasses = listOf(
        "PowerManager", "PeerFingerprintManager", "DebugSettingsManager", "SpamFilter",
        "FavoritesPersistenceService", "LiveVoiceManager", "SeenMessageStore", "ConversationRepository",
        "MeshGraphService", "ConversationListPreferences", "WearMeshService", "WearNotificationCoordinator",
        "MessageRouter",
    )

    /** Stateful `object`s: any lower-case member reached through the name is a static reach. */
    private val objects = listOf(
        "AppStateStore", "ContactDirectory", "TransportBridgeService", "PrivateMessageArrivalOrder",
        "VoiceWaveformCache", "SpamFilterPreferences", "TransferProgressManager", "DebugPreferenceManager",
        "GeohashAliasRegistry", "VerificationService", "WifiAwareController", "MeshServiceHolder",
        "MeshServicePreferences", "NostrIdentityBridge", "GeohashConversationRegistry",
        "TorPreferenceManager", "WearChatState", "WearPeerIdentityState",
    )

    /** Classes allowed to be reached statically anywhere, with the reason. */
    private val allowedClasses = mapOf(
        "MessageRouter" to "deferred to 10c-2a-2: mesh-replacement machinery the identity holder replaces",
    )

    /**
     * Files that are themselves still-static Scope classes and so cannot take constructor injection
     * (R7). Each is emptied by the task that flips the class named in the reason.
     */
    private val stillStatic = mapOf(
        "core/chat/src/main/java/com/bitchat/android/di/ChatBridgeModule.kt" to
            "bridge for ConversationListPreferences; deleted with the class's flip in Task 7",
        "core/chat/src/main/java/com/bitchat/android/services/ConversationListPreferences.kt" to
            "still-static class (getInstance); constructor-injected when flipped in Task 7",
        "core/chat/src/main/java/com/bitchat/android/services/MessageRouter.kt" to
            "deferred to 10c-2a-2: mesh-replacement machinery the identity holder replaces",
        "core/nostr/src/main/java/com/bitchat/android/nostr/NostrIdentity.kt" to
            "NostrIdentityBridge is a still-static object; flipped in Task 7",
    )

    /** Trees exempt until Task 3 moves the app and the watch. */
    private val exemptTrees = listOf("app/", "wear/")

    private val accessor = Regex(
        """(?<![\w@])(${(accessorClasses - allowedClasses.keys).joinToString("|")})\s*(?:\.|::)\s*""" +
            """(getInstance|tryGetInstance|shared|initialize|getOrCreate|peek)\b"""
    )

    private val objectReach = Regex(
        """(?<![\w@])(${objects.joinToString("|")})\s*(?:\.|::)\s*([a-z]\w*)"""
    )

    @Test
    fun noStaticMeshAccessOutsideMesh() {
        val offenders = mainSources().filter { (file, _) ->
            val path = file.relativeTo(root).invariantSeparatorsPath
            exemptTrees.none { path.startsWith(it) } && path !in stillStatic
        }.flatMap { (file, code) ->
            (accessor.findAll(code) + objectReach.findAll(code))
                .map { "${location(file, code, it.range.first)}: ${it.value.replace(Regex("\\s+"), "")}" }
                .toList()
        }
        assertTrue(
            "Mesh singletons must be injected, not reached statically:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    /** The scan itself: a reach split over lines, a property read and a callable reference all count. */
    @Test
    fun theScanSeesSplitLinePropertyAndReferenceReaches() {
        val code = codeOnly(
            """
            val a = AppStateStore
                .setNickname(n)
            val b = ContactDirectory.someVal
            val c = items.map(GeohashAliasRegistry::get)
            val d = FavoritesPersistenceService
                .shared
            val e = com.bitchat.android.services.AppStateStore.peers
            """.trimIndent()
        )
        val hits = (accessor.findAll(code) + objectReach.findAll(code)).count()
        assertTrue("Expected five reaches, found $hits", hits == 5)
    }

    @Test
    fun theStillStaticExemptionsExistAndStillReachAStatic() {
        val problems = stillStatic.keys.mapNotNull { path ->
            val file = File(root, path)
            when {
                !file.isFile -> "$path: no such file"
                codeOnly(file.readText()).let { accessor.containsMatchIn(it) || objectReach.containsMatchIn(it) } -> null
                else -> "$path: no longer reaches a static; drop it from stillStatic"
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /** Every Kotlin file under `<module>/src/main` below [trees], paired with its code. */
    private fun mainSources(trees: List<String> = listOf("app", "wear", "core/chat", "core/nostr", "core/tor", "feature")): List<Pair<File, String>> {
        val files = trees.map { File(root, it) }.flatMap { tree ->
            tree.walkTopDown()
                .onEnter { it.name != "build" }
                .filter { it.isFile && it.extension == "kt" && ("/src/main/" in it.invariantSeparatorsPath || "/src/debug/" in it.invariantSeparatorsPath) }
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
