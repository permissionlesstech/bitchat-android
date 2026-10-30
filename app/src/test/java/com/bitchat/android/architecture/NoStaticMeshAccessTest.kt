package com.bitchat.android.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mesh singletons are built by Hilt, so callers take them by injection and never reach one
 * through its class name, and none keeps a static holder. Read from every module's main and debug
 * sources, comments and string text dropped. The one exemption is [allowedClasses].
 */
class NoStaticMeshAccessTest {

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    /** Classes allowed to keep a static holder and be reached statically, with the reason. */
    private val allowedClasses = mapOf(
        "MessageRouter" to "deferred to 10c-2a-2: mesh-replacement machinery the identity holder replaces",
    )

    /**
     * The Scope classes of 10c-2a-1: each is an injected `@Singleton`, so a lower-case member
     * reached through the class name (`Name.foo`, `Name::foo`, `Name.Companion.foo`) is a static
     * reach, unless it is one of [companionFunctions].
     */
    private val scopeClasses = listOf(
        "PowerManager", "PeerFingerprintManager", "DebugSettingsManager", "SpamFilter",
        "FavoritesPersistenceService", "LiveVoiceManager", "SeenMessageStore", "ConversationRepository",
        "MeshGraphService", "ConversationListPreferences", "WearMeshService", "WearMeshHolder",
        "WearNotificationCoordinator", "AppStateStore", "ContactDirectory", "TransportBridgeService",
        "PrivateMessageArrivalOrder", "VoiceWaveformCache", "SpamFilterPreferences",
        "TransferProgressManager", "DebugPreferenceManager", "GeohashAliasRegistry", "VerificationService",
        "WifiAwareController", "MeshServiceHolder", "MeshServicePreferences", "MeshTransportToggles",
        "NostrIdentityBridge", "GeohashConversationRegistry", "TorPreferenceManager", "WearChatState",
        "WearPeerIdentityState",
    )

    /** Pure companion functions, which hold no state and may be called through the class name. */
    private val companionFunctions = setOf(
        "SpamFilter.isLocalNotice", "SpamFilter.meshSenderKey", "SpamFilter.nostrSenderKey",
        "LiveVoiceManager.burstIDFromVoiceFileName",
    )

    /** The stores the mesh and the screens must share: only Hilt (or a test) builds them. */
    private val sharedStores = listOf(
        "AppStateStore", "ContactDirectory", "TransportBridgeService", "FavoritesPersistenceService",
        "SpamFilter",
    )

    private val reach = Regex(
        """(?<![\w@])(${scopeClasses.joinToString("|")})\s*(?:\.\s*Companion\s*)?(\.|::)\s*([a-z]\w*)"""
    )

    private val holder = Regex("""\bfun\s+getInstance\s*\(|\bINSTANCE\b""")

    private val constructorCall = Regex(
        """(?<![\w@])(${sharedStores.joinToString("|")})\s*\(|::\s*(${sharedStores.joinToString("|")})\b(?!\s*::)"""
    )

    private val importAlias = Regex(
        """^\s*import\s+[\w.]*\.(${(scopeClasses + allowedClasses.keys).joinToString("|")})\s+as\s+\w+""",
        RegexOption.MULTILINE,
    )

    private val typeAlias = Regex(
        """\btypealias\s+\w+[^=\n]*=[^\n]*\b(${(scopeClasses + allowedClasses.keys).joinToString("|")})\b"""
    )

    @Test
    fun noScopeClassIsReachedThroughItsName() {
        val offenders = mainSources().flatMap { (file, code) -> reaches(file, code) }
        assertTrue(
            "Mesh singletons must be injected, not reached statically:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test
    fun noFileKeepsAStaticHolder() {
        val offenders = mainSources().filterNot { (file, _) -> file.nameWithoutExtension in allowedClasses }
            .flatMap { (file, code) -> holders(file, code) }
        assertTrue("Static singleton holders remain:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun noScopeClassIsAnObject() {
        val declaration = Regex("""\bobject\s+(${scopeClasses.joinToString("|")})\b""")
        val offenders = mainSources().flatMap { (file, code) ->
            declaration.findAll(code).map { "${location(file, code, it.range.first)}: object ${it.groupValues[1]}" }
        }
        assertTrue("Scope classes must be injected classes, not objects:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    /**
     * A Scope class's companion holds no mutable state: a companion is reachable through the bare
     * class name, so state there would be a static store beside the injected instance.
     */
    @Test
    fun noScopeClassCompanionHoldsState() {
        val offenders = mainSources()
            .filter { (file, _) -> file.nameWithoutExtension in scopeClasses }
            .flatMap { (file, code) -> companionState(file, code) }
        assertTrue("Scope class companions must hold no state:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    /** Hand-building a shared store gives the caller a second one the rest of the app never sees. */
    @Test
    fun onlyTheGraphBuildsTheSharedStores() {
        val offenders = mainSources().flatMap { (file, code) -> constructorCalls(file, code) }
        assertTrue(
            "Only Hilt builds the shared stores; take them by injection:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    /**
     * Creating the watch's mesh generates its identity keys, so only [WearMeshHolder] may be handed
     * one by the graph: anything else injecting it would create keys before permissions.
     */
    @Test
    fun onlyTheWearMeshHolderTakesTheWearMesh() {
        val offenders = mainSources(listOf("wear", "app", "core", "feature"))
            .filterNot { (file, _) -> file.name == "WearMeshHolder.kt" }
            .flatMap { (file, code) -> wearMeshInjections(file, code) }
        assertTrue(
            "Only WearMeshHolder may take WearMeshService from the graph:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
        val holderFile = File(root, "wear/src/main/java/com/bitchat/watch/mesh/WearMeshHolder.kt")
        assertTrue(
            "WearMeshHolder no longer takes Provider<WearMeshService>; the rule above checks nothing",
            wearMeshInjections(holderFile, codeOnly(holderFile.readText())).isNotEmpty()
        )
    }

    /** No Scope class is imported under another name, which would hide its reaches from the scan. */
    @Test
    fun noScopeClassIsImportedUnderAnAlias() {
        val offenders = mainSources().flatMap { (file, code) ->
            importAlias.findAll(code).map { "${location(file, code, it.range.first)}: ${it.value.trim()}" }
        }
        assertTrue("Scope classes must not be imported under an alias:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    /**
     * No typealias names a Scope class: `Store(...)` or `@Inject constructor(m: Mesh)` through an
     * alias would build a second store or take the watch mesh unseen by the rules above.
     */
    @Test
    fun noScopeClassHasATypealias() {
        val offenders = mainSources().flatMap { (file, code) ->
            typeAlias.findAll(code).map { "${location(file, code, it.range.first)}: ${it.value.trim()}" }
        }
        assertTrue("Scope classes must not be given a typealias:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    /** The scan itself: each form of static reach it is meant to see is seen, and allowed forms are not. */
    @Test
    fun theScanSeesEachFormOfReach() {
        val code = codeOnly(
            """
            val a = AppStateStore
                .setNickname(n)
            val b = ContactDirectory.someVal
            val c = items.map(GeohashAliasRegistry::get)
            val d = FavoritesPersistenceService
                .shared
            val e = com.bitchat.android.services.AppStateStore.peers
            val f = SpamFilter.Companion.shared
            val g = MessageRouter.getInstance(context)
            val h = SpamFilter.meshSenderKey(message)
            val i = AppStateStore::class.java
            val j = BluetoothMeshService.Factory
            fun ConversationListPreferences.composerDraft() = Unit
            // AppStateStore.ignoredInComment
            val k = "AppStateStore.ignoredInString"
            """.trimIndent()
        )
        assertEquals(
            listOf("AppStateStore.setNickname", "ContactDirectory.someVal", "GeohashAliasRegistry::get",
                "FavoritesPersistenceService.shared", "AppStateStore.peers", "SpamFilter.shared"),
            reaches(File(root, "Sample.kt"), code).map { it.substringAfter(": ") },
        )
    }

    @Test
    fun theScanSeesHoldersConstructorCallsWearMeshInjectionsAndAliases() {
        val code = codeOnly(
            """
            import com.bitchat.android.services.AppStateStore as Store
            typealias Store = AppStateStore
            typealias Meshes = List<com.bitchat.watch.mesh.WearMeshService>
            typealias Peers = List<String>
            class Thing @Inject constructor(
                private val mesh: Provider<WearMeshService>,
            ) {
                @Inject lateinit var direct: WearMeshService
                private fun mesh(): WearMeshService = holder.getOrCreate()
                companion object {
                    @Volatile private var INSTANCE: Thing? = null
                    val shared = Thing()
                    fun getInstance(): Thing = INSTANCE!!
                }
                fun local() { val shared = 1 }
                val store = AppStateStore(directory, order, lazyRepos, null)
                val filters = list.map(::SpamFilter)
                val type = AppStateStore::class
            }
            @Singleton
            class AppStateStore(private val directory: ContactDirectory)
            interface Access { fun wearMesh(): WearMeshService }
            class Other @AssistedInject constructor(@Assisted id: String, mesh: com.bitchat.watch.mesh.WearMeshService)
            class Late @Inject constructor(mesh: Provider<com.bitchat.watch.mesh.WearMeshService>)
            """.trimIndent()
        )
        val file = File(root, "Sample.kt")
        assertEquals(4, holders(file, code).size)
        assertEquals(listOf("AppStateStore(", "::SpamFilter"), constructorCalls(file, code).map { it.substringAfter(": ") })
        assertEquals(5, wearMeshInjections(file, code).size)
        assertEquals(1, importAlias.findAll(code).count())
        assertEquals(listOf("AppStateStore", "WearMeshService"), typeAlias.findAll(code).map { it.groupValues[1] }.toList())
        assertEquals(listOf("var"), companionState(file, code).map { it.substringAfter(": ") })
    }

    private fun reaches(file: File, code: String): List<String> =
        reach.findAll(code).filterNot { match ->
            val (name, _, member) = match.destructured
            member == "class" ||
                "$name.$member" in companionFunctions ||
                code.substring(0, match.range.first).trimEnd().endsWith("fun")
        }.map { "${location(file, code, it.range.first)}: ${it.groupValues[1]}${it.groupValues[2]}${it.groupValues[3]}" }
            .toList()

    /**
     * `fun getInstance(` and `INSTANCE` anywhere, and a `shared` property directly in an `object`
     * or `companion object` body (a local `val shared` in a function is not a holder).
     */
    private fun holders(file: File, code: String): List<String> {
        val declared = holder.findAll(code).map { it.range.first to it.value }.toList()
        val shared = objectBodies(code).flatMap { (start, body) ->
            Regex("""\b(?:val|var)\s+shared\b""").findAll(body).map { start + it.range.first to it.value }.toList()
        }
        return (declared + shared).sortedBy { it.first }
            .map { (offset, value) -> "${location(file, code, offset)}: ${value.replace(Regex("\\s+"), " ")}" }
    }

    private fun constructorCalls(file: File, code: String): List<String> =
        constructorCall.findAll(code).filterNot { match ->
            code.substring(0, match.range.first).trimEnd().endsWith("class")
        }.map { "${location(file, code, it.range.first)}: ${it.value.replace(Regex("\\s+"), "")}" }.toList()

    /**
     * Places the graph hands over a [WearMeshService]: a `Provider`/`Lazy` of it, an `@Inject`
     * field, constructor or `@Provides` parameter of its type, or an entry-point accessor (an
     * abstract function returning it).
     */
    private fun wearMeshInjections(file: File, code: String): List<String> {
        val type = """\b(?:[\w.]+\.)?WearMeshService\b"""
        val wrapped = Regex("""\b(?:Provider|Lazy)\s*<\s*$type""").findAll(code).map { it.range.first }
        val field = Regex("""@Inject\s+(?:lateinit\s+)?(?:var|val)\s+\w+\s*:\s*$type""").findAll(code).map { it.range.first }
        val parameters = Regex("""@(?:Assisted)?Inject\s+(?:\w+\s+)?constructor\s*\(|@Provides\b[^(]*\(""").findAll(code)
            .filter { Regex(""":\s*$type""").containsMatchIn(parenthesised(code, it.range.last)) }
            .map { it.range.first }
        val accessor = Regex("""\bfun\s+\w+\s*\(\s*\)\s*:\s*$type\??[ \t]*(?=\n|}|$)""").findAll(code).map { it.range.first }
        return (wrapped + field + parameters + accessor).toSortedSet()
            .map { "${location(file, code, it)}: takes WearMeshService" }
    }

    /** Mutable state declared directly in a `companion object` body in [code]. */
    private fun companionState(file: File, code: String): List<String> {
        val state = Regex("""\b(?:var|lateinit)\b|\bMutable[A-Z]\w*|\bConcurrentHashMap\b|\bmutable(?:List|Map|Set)Of\b""")
        return objectBodies(code)
            .filter { (start, _) -> Regex("""companion\s+object\b[^{]*\{$""").containsMatchIn(code.substring(0, start)) }
            .flatMap { (start, body) ->
                state.findAll(body).map { "${location(file, code, start + it.range.first)}: ${it.value}" }.toList()
            }
    }

    /** The text between the `(` at [open] and its matching `)`. */
    private fun parenthesised(code: String, open: Int): String {
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return code.substring(open + 1, i)
            }
        }
        return code.substring(open + 1)
    }

    /**
     * The direct body of each `object` and `companion object` in [code], with nested blocks
     * blanked, paired with the offset the body starts at.
     */
    private fun objectBodies(code: String): List<Pair<Int, String>> =
        Regex("""\bobject\b[^{}()=]*\{""").findAll(code).map { match ->
            val start = match.range.last + 1
            val body = StringBuilder()
            var depth = 1
            var i = start
            while (i < code.length && depth > 0) {
                val c = code[i]
                when (c) {
                    '{' -> depth++
                    '}' -> depth--
                }
                body.append(if (depth == 1 && c != '}' || c == '\n') c else ' ')
                i++
            }
            start to body.toString()
        }.toList()

    /** Every Kotlin file under `<module>/src/main` and `src/debug` below [trees], paired with its code. */
    private fun mainSources(trees: List<String> = listOf("app", "wear", "core", "feature")): List<Pair<File, String>> {
        val files = trees.map { File(root, it) }.flatMap { tree ->
            tree.walkTopDown()
                .onEnter { it.name != "build" }
                .filter { it.isFile && it.extension == "kt" && ("/src/main/" in it.invariantSeparatorsPath || "/src/debug/" in it.invariantSeparatorsPath) }
                .toList()
        }
        assertTrue("Scanned no Kotlin sources; the source roots moved", files.isNotEmpty())
        assertTrue(
            "core/mesh is no longer scanned",
            files.any { "core/mesh/src/main/" in it.invariantSeparatorsPath }
        )
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
