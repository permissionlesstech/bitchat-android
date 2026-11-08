package com.bitchat.android.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every singleton declares its lifetime, and identity types are reached only the allowed ways.
 *
 * An identity type is one that belongs to a single identity: a class annotated `@IdentityScoped`
 * or one a `@CurrentIdentity` `@Provides` bridge hands out. Outside the identity scope it is taken
 * as `@CurrentIdentity Provider<T>`, so every `get()` asks the holder and nothing caches an
 * identity's object past a wipe. Read as source text from the module sources, comments and string
 * literals dropped. The tables below are the allowlists.
 *
 * The construction rule scans only `init` blocks and property initialisers, so `init { setup() }`
 * and a method that stores `provider.get()` in a field are not caught. The Dagger qualifier is the
 * primary enforcement; this scan is a backstop.
 */
class IdentityScopeRulesTest {

    // ---- Allowlists -------------------------------------------------------------------------

    /** Singletons that are process-wide on purpose, with the reason. */
    private val processWide: Map<String, String> = mapOf(
        "IdentityHolder" to "owns the identity component, so it outlives every identity",
        "ArtiTorManager" to "network route only; holds no identity data",
        "OkHttpProvider" to "network route only; holds no identity data",
        "TorPreferenceManager" to "network route only; holds no identity data",
        "NostrHttpClients" to "the Tor-routed HTTP clients; network route only",
        "NostrRelayManager" to "sockets and relay list; identity subscriptions belong to their owners",
        "RelayDirectory" to "sockets and relay list; identity subscriptions belong to their owners",
        "LiveLocationPrivacyGate" to "consent gate; panic turns location off",
        "PowerManager" to "device and process state",
        "AppShutdownCoordinator" to "device and process state",
        "MeshServicePreferences" to "device and process state",
        "DeviceReadiness" to "reads the device and stored onboarding choices; no identity data",
        "DebugSettingsManager" to "debug settings; the wipe list must cover its buffers",
        "DebugPreferenceManager" to "debug settings; the wipe list must cover its buffers",
        "SpamFilterPreferences" to "settings only",
        "PoWPreferenceManager" to "settings only",
        "WearNotificationCoordinator" to "system notifications",
    )

    /**
     * Unscoped classes the identity scope builds, which take identity types like an `@IdentityScoped`
     * class does but cannot carry the annotation, with the reason.
     */
    private val builtInsideTheScope: Map<String, String> = mapOf(
        "BluetoothMeshService" to "@AssistedInject, which Dagger refuses to scope; built only by MeshServiceHolder",
        "UnifiedMeshService" to "@AssistedInject, which Dagger refuses to scope; built only by MeshServiceHolder",
        "MessageRouter" to "@AssistedInject, which Dagger refuses to scope; built only by MessageRouterHolder",
        "WifiAwareMeshService" to "a fresh instance per start, so unscoped; built only by WifiAwareController",
    )

    /**
     * Every `@ActivityRetainedScoped` class, `@HiltViewModel`, and type a module installed in
     * `ActivityRetainedComponent` provides: the chat-session classes allowed a direct
     * `@CurrentIdentity X`. The chat session change empties it.
     */
    private val pendingChatSession: Set<String> = setOf(
        "AboutViewModel", "ApkDownloadViewModel", "AppInitializer", "AppNavigator",
        "AppStartupGate", "BluetoothSkipSession", "ChannelManager", "ChatBackUnwinder",
        "ChatMeshDelegate", "ChatSessionMesh", "ChatSessionStartup", "ChatState",
        "ChatUserViewModel", "ChatViewModel", "CommandProcessor", "ComposerMedia",
        "ContactFavorites", "ConversationDirectory", "CoroutineScope", "DataManager",
        "DebugSettingsViewModel", "EntryProviderInstaller", "GeohashSession", "HotspotViewModel",
        "LocationChannelsViewModel", "LocationNotesViewModel", "MediaSendingManager", "MeshDelegateHandler",
        "MeshPeerListViewModel", "MessageManager", "MessageSender", "Navigator",
        "NotificationManager", "OnboardingHost", "OnboardingViewModel", "PanicClear",
        "PeerListLookups", "PrivateChatManager", "PrivateChatSession", "PrivateChatViewModel",
        "SecureIdentityStateManager", "SecurityVerificationViewModel", "VerificationHandler", "VerificationViewModel",
    )

    /**
     * Stateful Kotlin objects, with the reason. A companion is `Owner.Companion`. Entries that hold
     * an identity's data are marked identity: they are still to be moved into the scope.
     */
    private val statefulObjects: Map<String, String> = mapOf(
        "SecureIdentityStateManager.Companion" to "identity: private-media pin epoch counter, still to be reviewed",
        "NotificationManager.Companion" to "identity: registry of live managers; chat-session lifetime",
        "ChatUiModeManager" to "process: display preference flow",
        "ThemePreferenceManager" to "process: display preference flow",
        "FileShareDispatcher" to "process: the foreground screen's share handler; holds no identity data",
        "LandData" to "process: decoded static globe geometry cache",
        "ReadinessOverride" to "process: debug-only readiness override",
        "TestHookDriver" to "process: debug-only test hook",
        "WearTestHookDriver" to "process: debug-only test hook",
    )

    // ---- Tests against the real sources -----------------------------------------------------

    @Test
    fun everySingletonIsProcessWideWithAReason() {
        val model = realModel()
        assertTrue("Scanned no singletons; the scan is broken", model.singletons().size > 10)
        val offenders = singletonViolations(model, processWide)
        assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun theAllowlistsHoldNoStaleOrContradictoryEntry() {
        val problems = tableProblems(realModel(), processWide, pendingChatSession)
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun aClassBuiltInsideTheScopeIsUnscopedAndGivesItsReason() {
        val problems = insideScopeProblems(realModel(), builtInsideTheScope)
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun aStatefulObjectIsAllowlistedWithAReason() {
        val model = realModel()
        val offenders = statefulObjectViolations(model, statefulObjects)
        assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun outsideTheScopeAnIdentityTypeIsTakenAsACurrentIdentityProvider() {
        val model = realModel()
        assertTrue("No identity type found; the scan is broken", model.identityTypes().isNotEmpty())
        val offenders = providerViolations(model, pendingChatSession, builtInsideTheScope.keys)
        assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun onlyTheChatSessionAndViewModelsMayTakeAnIdentityTypeDirectly() {
        val offenders = directViolations(realModel(), pendingChatSession, builtInsideTheScope.keys)
        assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun aProcessLivedClassDoesNotCallAProviderOfAnIdentityTypeWhileBeingBuilt() {
        val offenders = constructionViolations(realModel())
        assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
    }

    // ---- Each rule, proven against synthetic source -----------------------------------------

    private val identityCache = """
        package x
        @IdentityScoped
        class Cache @Inject constructor()
    """.trimIndent()

    @Test
    fun theSingletonRuleSeesAnUnclassifiedClassAndAProvidedSingleton() {
        val model = modelOf(
            "@Singleton class Known @Inject constructor()",
            "@Singleton class Stray @Inject constructor()",
            "@javax.inject.Singleton class Qualified @javax.inject.Inject constructor()",
            "@Module object M { @Provides @Singleton fun strayProvided(a: A): Provided = Provided(a) }",
            "@Module abstract class B { @Binds @Singleton abstract fun bind(i: Impl): Bound }",
            "// @Singleton class Commented()\n/* @Singleton class Blocked() */ val s = \"@Singleton class Quoted()\"",
        )
        val found = singletonViolations(model, mapOf("Known" to "why", "Bound" to "why"))
        assertEquals(found.toString(), 3, found.size)
        listOf("Stray", "Provided", "Qualified").forEach { name -> assertTrue(found.any { it.contains("'$name'") }) }
        assertTrue(found.all { "PROCESS_WIDE" in it && "@IdentityScoped" in it })
    }

    @Test
    fun theTablesRefuseAStaleBlankOrDoubleEntry() {
        val model = modelOf(
            identityCache,
            "@Singleton class Gone @Inject constructor()",
            "@IdentityScoped @Singleton class TwoLifetimes @Inject constructor()",
        )
        val problems = tableProblems(
            model,
            processWide = mapOf("Missing" to "why", "Blank" to " ", "Cache" to "why"),
            pendingChatSession = setOf("NotChatSession"),
        )
        listOf("Missing", "Blank", "Cache", "TwoLifetimes", "NotChatSession").forEach { name ->
            assertTrue("no problem named for $name in $problems", problems.any { name in it })
        }
    }

    @Test
    fun theObjectRuleSeesEachFormOfState() {
        val model = modelOf(
            "object ByVar { private var count = 0 }",
            "object ByFlow { val flow = MutableStateFlow(0) }",
            "object ByMap { val map = ConcurrentHashMap<String, Int>() }",
            "object ByList {\n val list =\n   mutableListOf<Int>()\n}",
            "class Owner { companion object { @Volatile private var cache: Int? = null } }",
            "object Allowed { var x = 0 }",
            "object Constants { const val A = 1; val B = listOf(1); fun f() { var local = 0 } }",
            "val anonymous = object : Runnable { var n = 0; override fun run() {} }",
        )
        val found = statefulObjectViolations(model, mapOf("Allowed" to "why"))
        listOf("ByVar", "ByFlow", "ByMap", "ByList", "Owner.Companion").forEach { name ->
            assertTrue("$name not reported in $found", found.any { it.contains("'$name'") })
        }
        assertEquals(found.toString(), 5, found.size)
        val stale = statefulObjectViolations(model, mapOf("Constants" to "why", "Allowed" to "why"))
        assertTrue("a stateless allowlisted object should be reported: $stale", stale.any { it.contains("'Constants'") })
    }

    @Test
    fun theProviderRuleSeesEveryDirectLazyAndUnqualifiedUse() {
        val model = modelOf(
            identityCache,
            "@Singleton class Direct @Inject constructor(private val c: Cache)",
            "@Singleton class FullyQualified @javax.inject.Inject constructor(private val c: com.x.Cache)",
            "@Singleton class Qualified @Inject constructor(@CurrentIdentity private val c: Cache)",
            "@Singleton class Lazily @Inject constructor(@CurrentIdentity private val c: Lazy<Cache>)",
            "@Singleton class Plain @Inject constructor(private val c: Provider<Cache>)",
            "@Singleton class Good @Inject constructor(@CurrentIdentity private val c: Provider<Cache>)",
            "@Singleton class Multi @Inject constructor(\n private val a: Int,\n @param:CurrentIdentity private val c: Provider<Cache>,\n)",
            "class Activity { @Inject lateinit var field: Cache\n @Inject @CurrentIdentity lateinit var good: Provider<Cache> }",
            "@EntryPoint @InstallIn(SingletonComponent::class) interface Hook { fun cache(): Cache\n @CurrentIdentity fun ok(): Cache }",
            "@Module @InstallIn(SingletonComponent::class) object Bridge { @Provides @CurrentIdentity fun c(h: H): Cache = h.x() }",
            "@Module @InstallIn(SingletonComponent::class) object Leak { @Provides fun c(h: H): Cache = h.x() }",
            "@Module @InstallIn(SingletonComponent::class) object Takes { @Provides fun t(c: Cache): Other = Other(c) }",
            "@Module @InstallIn(IdentityComponent::class) object Inside { @Provides fun t(c: Cache): Other = Other(c) }",
            "@EntryPoint @InstallIn(IdentityComponent::class) interface InsideHook { fun cache(): Cache }",
            "@IdentityScoped class Sibling @Inject constructor(private val c: Cache)",
            "class NotInjected(private val c: Cache)",
            "@Singleton class SecLazy(private val n: Int) {\n @Inject constructor(@CurrentIdentity c: Lazy<Cache>) : this(1)\n}",
            "@Singleton class SecPlain(private val n: Int) {\n @Inject constructor(c: Provider<Cache>) : this(1)\n}",
            "@Singleton class SecDirect(private val n: Int) {\n @Inject constructor(c: Cache) : this(1)\n}",
            "@Singleton class SecGood(private val c: Cache) {\n @Inject constructor(@CurrentIdentity p: Provider<Cache>) : this(p.get())\n}",
        )
        val found = providerViolations(model, emptySet())
        val expected = listOf("SecLazy", "SecPlain", "SecDirect", "Direct", "FullyQualified", "Lazily", "Plain", "Activity", "Hook", "Leak", "Takes")
        expected.forEach { name -> assertTrue("$name not reported in $found", found.any { it.contains("'$name") }) }
        listOf("SecGood", "Qualified", "Good", "Multi", "Bridge", "Inside", "InsideHook", "Sibling", "NotInjected").forEach { name ->
            assertTrue("$name wrongly reported in $found", found.none { it.contains("'$name") })
        }
    }

    @Test
    fun aClassBuiltInsideTheScopeMayTakeAnIdentityTypeOnlyWhenListedAndUnscoped() {
        val model = modelOf(
            identityCache,
            "class Listed @AssistedInject constructor(private val c: Cache, @Assisted n: Int)",
            "class Unlisted @AssistedInject constructor(private val c: Cache, @Assisted n: Int)",
            "@Singleton class Scoped @Inject constructor(private val c: Provider<Cache>)",
        )
        val found = providerViolations(model, emptySet(), setOf("Listed"))
        assertTrue("Listed wrongly reported in $found", found.none { it.contains("'Listed") })
        assertTrue("Unlisted not reported in $found", found.any { it.contains("'Unlisted") })
        val problems = insideScopeProblems(model, mapOf("Listed" to "why", "Scoped" to "why", "Gone" to "why", "Cache" to "why", "Unlisted" to " "))
        listOf("Scoped", "Gone", "Cache", "Unlisted").forEach { name ->
            assertTrue("no problem named for $name in $problems", problems.any { name in it })
        }
        assertTrue("Listed wrongly reported in $problems", problems.none { "Listed" in it.replace("Unlisted", "") })
    }

    @Test
    fun aBridgeWithoutAnExplicitReturnTypeFails() {
        val model = modelOf(
            identityCache,
            "@Module @InstallIn(SingletonComponent::class) object NoType { @Provides @CurrentIdentity fun c(h: H) = h.x() }",
        )
        val found = providerViolations(model, emptySet())
        assertEquals(found.toString(), 1, found.size)
        assertTrue(found.single().contains("explicit return type") && found.single().contains("'c'"))
    }

    @Test
    fun theDirectRuleAllowsOnlyAListedChatSessionClass() {
        val model = modelOf(
            identityCache,
            "@ActivityRetainedScoped class Session @Inject constructor(@CurrentIdentity private val c: Cache)",
            "@ActivityRetainedScoped class Listed @Inject constructor(@CurrentIdentity private val c: Cache)",
            "@HiltViewModel class Vm @Inject constructor(@CurrentIdentity private val c: Cache) : ViewModel()",
            "@Singleton class Process @Inject constructor(@CurrentIdentity private val c: Cache)",
            "class Activity { @Inject @CurrentIdentity lateinit var c: Cache }",
            "@Module @InstallIn(ActivityRetainedComponent::class) object M { @Provides @ActivityRetainedScoped fun s(@CurrentIdentity c: Cache): Made = Made(c) }",
            "@Module @InstallIn(ActivityRetainedComponent::class) object Unscoped { @Provides fun u(@CurrentIdentity c: Cache): InModule = InModule(c) }",
            "@Module @InstallIn(SingletonComponent::class) object Proc { @Provides fun u(@CurrentIdentity c: Cache): InProcess = InProcess(c) }",
            "@EntryPoint @InstallIn(SingletonComponent::class) interface EP { @CurrentIdentity fun c(): Cache }",
        )
        assertTrue("a module installed in ActivityRetainedComponent is chat-session", "InModule" in model.chatSessionNames())
        assertTrue("a SingletonComponent module or entry point is not", listOf("InProcess", "EP", "Cache").none { it in model.chatSessionNames() })
        val found = directViolations(model, setOf("Listed", "Vm", "InModule"))
        listOf("Session", "Process", "Activity", "M").forEach { name ->
            assertTrue("$name not reported in $found", found.any { it.contains("'$name'") })
        }
        assertTrue("InProcess not reported in $found", found.any { it.contains("InProcess") || it.contains("'Proc'") })
        listOf("Listed", "Vm").forEach { name ->
            assertTrue("$name wrongly reported in $found", found.none { it.contains("'$name'") })
        }
        assertTrue("InModule wrongly reported in $found", found.none { it.contains("'Unscoped'") })
        assertTrue("an entry point accessor is fine: $found", found.none { it.contains("'EP'") })
    }

    @Test
    fun theConstructionRuleSeesAProviderCalledWhileBuilding() {
        val model = modelOf(
            identityCache,
            "@Singleton class Init @Inject constructor(@CurrentIdentity private val p: Provider<Cache>) {\n init { p.get().start() }\n}",
            "@Singleton class Prop @Inject constructor(@CurrentIdentity private val p: Provider<Cache>) {\n private val c = p.get()\n}",
            "@Singleton class PropWrapped @Inject constructor(@CurrentIdentity private val p: Provider<Cache>) {\n private val n = p\n   .get()\n   .name\n}",
            "@Singleton class Holder @Inject constructor(private val holder: IdentityHolder) {\n private val c = holder.entryPoint<Hook>()\n}",
            "@Singleton class Later @Inject constructor(@CurrentIdentity private val p: Provider<Cache>) {\n" +
                " private val lazyOne by lazy { p.get() }\n" +
                " private val lazyTwo = lazy { p.get() }\n" +
                " private val gap: Cache get() = p.get()\n" +
                " fun use() = p.get().start()\n" +
                " fun run() {\n  p.get().start()\n }\n" +
                " init { scope.launch { p.get().start() } }\n" +
                "}",
            "class NotProcess @Inject constructor(@CurrentIdentity private val p: Provider<Cache>) {\n init { p.get() }\n}",
            "@Singleton class Run @Inject constructor(@CurrentIdentity private val p: Provider<Cache>) {\n private val c = run { p.get() }\n}",
        )
        val found = constructionViolations(model)
        listOf("Init", "Prop", "PropWrapped", "Holder", "Run").forEach { name ->
            assertTrue("$name not reported in $found", found.any { it.contains("'$name'") })
        }
        listOf("Later", "NotProcess").forEach { name ->
            assertTrue("$name wrongly reported in $found", found.none { it.contains("'$name'") })
        }
    }

    @Test
    fun theScanDropsCommentsAndStrings() {
        val code = mask("val a = \"// not a comment\" // real\n/* x\n y */ val b = '{' \"\"\"raw \" {\"\"\"")
        assertEquals(2, code.count { it == '\n' })
        assertTrue("//" !in code && "real" !in code && "{" !in code && "raw" !in code)
        assertEquals(listOf("a", "b"), Regex("""val (\w)""").findAll(code).map { it.groupValues[1] }.toList())
    }

    // ---- Rules ------------------------------------------------------------------------------

    private fun singletonViolations(model: Model, processWide: Map<String, String>): List<String> =
        model.singletons().filter { (name, _) -> name !in processWide }.map { (name, file) ->
            "'$name' ($file) is a singleton that declares no lifetime. If it holds one identity's data, " +
                "make it @IdentityScoped; if it is process-wide on purpose, " +
                "add it to PROCESS_WIDE with the reason."
        }

    private fun tableProblems(
        model: Model,
        processWide: Map<String, String>,
        pendingChatSession: Set<String>,
    ): List<String> {
        val declared = model.declaredNames()
        val identity = model.scopedClasses()
        val problems = mutableListOf<String>()
        processWide.keys.filter { it !in declared }.forEach {
            problems += "'$it' is in an allowlist but no such class or provided type exists; remove the stale entry."
        }
        processWide.filterValues { it.isBlank() }.keys.forEach {
            problems += "'$it' is in PROCESS_WIDE without a reason; write why it holds no identity's data."
        }
        (processWide.keys intersect identity).forEach {
            problems += "'$it' is @IdentityScoped but listed in PROCESS_WIDE; remove it."
        }
        model.classes.filter { it.has("IdentityScoped") && it.has("Singleton") }.forEach {
            problems += "'${it.name}' (${it.file}) is both @IdentityScoped and @Singleton; it has one lifetime."
        }
        val chatSession = model.chatSessionNames()
        (pendingChatSession.filter { it !in chatSession }).forEach {
            problems += "'$it' is in PENDING_CHAT_SESSION but is no @ActivityRetainedScoped or @HiltViewModel class; remove it."
        }
        return problems
    }

    private fun statefulObjectViolations(model: Model, allowed: Map<String, String>): List<String> {
        val stateful = model.classes.filter { it.kind == "object" }.mapNotNull { decl ->
            val body = decl.bodyText(model) ?: return@mapNotNull null
            val state = members(body).firstOrNull(::isStatefulMember) ?: return@mapNotNull null
            Triple(model.objectKey(decl), decl.file, state.lineSequence().first().trim())
        }
        val names = stateful.map { it.first }.toSet()
        val unlisted = stateful.filter { it.first !in allowed }.map { (name, file, member) ->
            "'$name' ($file) is an object with state (`$member`). State in an object outlives an identity: " +
                "move it into a scoped class, or add it to STATEFUL_OBJECTS with the reason."
        }
        val stale = allowed.keys.filter { it !in names }.map {
            "'$it' is in STATEFUL_OBJECTS but is not a stateful object any more; remove the entry."
        }
        val blank = allowed.filterValues { it.isBlank() }.keys.map { "'$it' is in STATEFUL_OBJECTS without a reason." }
        return unlisted + stale + blank
    }

    private fun insideScopeProblems(model: Model, table: Map<String, String>): List<String> =
        table.flatMap { (name, reason) ->
            val decl = model.classes.firstOrNull { it.name == name && it.kind == "class" }
            listOfNotNull(
                "'$name' is built inside the scope by the table but no such class exists; remove the entry.".takeIf { decl == null },
                "'$name' carries a scope; a scoped class does not belong in the built-inside table."
                    .takeIf { decl != null && (decl.has("Singleton") || decl.has("IdentityScoped")) },
                "'$name' is built inside the scope by the table without a reason.".takeIf { reason.isBlank() },
            )
        }

    private fun providerViolations(model: Model, chatAllowed: Set<String>, insideScope: Set<String> = emptySet()): List<String> =
        model.bridges().filter { it.returnName.isEmpty() }.map {
            "'${it.name}' (${it.file}) is a @CurrentIdentity bridge without an explicit return type; " +
                "the bridge needs an explicit return type so the scan knows which type it hands out."
        } + model.sites().filter { it.owner !in insideScope }.flatMap { site -> site.problem(model.identityTypes(), chatAllowed, directAllowedKinds = false) }

    private fun directViolations(model: Model, chatAllowed: Set<String>, insideScope: Set<String> = emptySet()): List<String> =
        model.sites().filter { it.owner !in insideScope }.flatMap { site -> site.problem(model.identityTypes(), chatAllowed, directAllowedKinds = true) }

    private fun constructionViolations(model: Model): List<String> {
        val identity = model.identityTypes()
        return model.classes.filter { it.kind == "class" && it.has("Singleton") && !it.has("IdentityScoped") }.flatMap { decl ->
            val body = decl.bodyText(model) ?: return@flatMap emptyList()
            val watched = (listOfNotNull(decl.ctor) + model.secondaryInjectConstructors(decl)).flatMap { params(it) }.filter { p ->
                providerOf(p.type)?.let { it in identity } == true || p.type.trim() == "IdentityHolder"
            }.map { it.name }
            if (watched.isEmpty()) return@flatMap emptyList()
            val built = constructionCode(body)
            watched.filter { name ->
                Regex("""\b$name\s*\.\s*(get|entryPoint|current)\b""").containsMatchIn(built)
            }.map { name ->
                "'${decl.name}' (${decl.file}) reads `$name` while it is being built (an initialiser or init block). " +
                    "A process-lived class must read an identity's Provider inside a function, when it is used."
            }
        }
    }

    // ---- Model ------------------------------------------------------------------------------

    private class ClassDecl(
        val file: String,
        val kind: String,
        val name: String,
        val annotations: String,
        val ctorPrefix: String,
        val ctor: String?,
        val start: Int,
        val bodyStart: Int,
        val bodyEnd: Int,
    ) {
        fun has(annotation: String) = Regex("""@(?:\w+:)?(?:[a-z]\w*\.)*$annotation\b""").containsMatchIn(annotations)
        val hasBody get() = bodyStart >= 0
        fun bodyText(model: Model): String? = if (hasBody) model.codeOf(file).substring(bodyStart + 1, bodyEnd) else null
    }

    private class FunDecl(
        val file: String,
        val name: String,
        val annotations: String,
        val params: String,
        val returnType: String,
        val index: Int,
    ) {
        fun has(annotation: String) = Regex("""@(?:\w+:)?(?:[a-z]\w*\.)*$annotation\b""").containsMatchIn(annotations)
        val returnName get() = baseName(returnType)
    }

    private enum class Kind { CONSTRUCTOR, FIELD, ENTRY_POINT, PROVIDES_PARAM, PROVIDES_RETURN }

    private class Site(
        val file: String,
        val owner: String,
        val ownerChatSession: Boolean,
        val kind: Kind,
        val what: String,
        val label: String,
        val annotations: String,
        val type: String,
    ) {
        /** [directAllowedKinds] picks the rule: false is the Provider rule, true the direct rule. */
        fun problem(identity: Set<String>, chatAllowed: Set<String>, directAllowedKinds: Boolean): List<String> {
            val t = type.trim().replace(Regex("""\b[a-z]\w*\."""), "")
            val qualified = Regex("""@(?:\w+:)?(?:[a-z]\w*\.)*CurrentIdentity\b""").containsMatchIn(annotations)
            val provided = providerOf(t)
            val lazyOf = Regex("""^(?:[\w.]*\.)?Lazy\s*<\s*(\w+)\s*>$""").find(t)?.groupValues?.get(1)
            val direct = t.removeSuffix("?").trim().takeIf { it in identity }
            val mentioned = identity.filter { Regex("""\b$it\b""").containsMatchIn(t) }
            val where = "'$label' ($file), $what"
            return when {
                provided != null && provided in identity ->
                    if (directAllowedKinds || qualified) emptyList() else listOf(
                        "$where takes Provider<$provided> without @CurrentIdentity. Add @CurrentIdentity so the process graph supplies it."
                    )
                lazyOf != null && lazyOf in identity ->
                    if (directAllowedKinds) emptyList() else listOf(
                        "$where takes Lazy<$lazyOf>, which caches across identities. Take @CurrentIdentity Provider<$lazyOf> and call get() when used."
                    )
                direct != null && qualified ->
                    when {
                        !directAllowedKinds -> emptyList()
                        kind == Kind.ENTRY_POINT || kind == Kind.PROVIDES_RETURN -> emptyList()
                        ownerChatSession && owner in chatAllowed -> emptyList()
                        else -> listOf(
                            "$where takes @CurrentIdentity $direct directly. Only a chat-session class or ViewModel listed in " +
                                "PENDING_CHAT_SESSION may; take @CurrentIdentity Provider<$direct> instead."
                        )
                    }
                direct != null ->
                    if (directAllowedKinds) emptyList() else listOf(
                        "$where takes $direct outside the identity scope. Take @CurrentIdentity Provider<$direct> and call get() when used."
                    )
                mentioned.isNotEmpty() ->
                    if (directAllowedKinds) emptyList() else listOf(
                        "$where takes `$t`, which wraps ${mentioned.first()} outside the identity scope. " +
                            "Take @CurrentIdentity Provider<${mentioned.first()}> instead."
                    )
                else -> emptyList()
            }
        }
    }

    private class Src(val path: String, val code: String)

    private class Model(val sources: List<Src>) {
        val classes: List<ClassDecl>
        val functions: List<FunDecl>

        init {
            val cs = mutableListOf<ClassDecl>()
            val fs = mutableListOf<FunDecl>()
            sources.forEach { src ->
                cs += parseClasses(src)
                fs += parseFunctions(src)
            }
            classes = cs
            functions = fs
        }

        private val byPath = sources.associate { it.path to it.code }
        fun codeOf(path: String) = byPath.getValue(path)

        /** Innermost-first chain of declarations whose body holds [index] of [file]. */
        fun enclosing(file: String, index: Int): List<ClassDecl> =
            classes.filter { it.file == file && it.hasBody && index > it.bodyStart && index < it.bodyEnd }
                .sortedByDescending { it.bodyStart }

        fun objectKey(decl: ClassDecl): String {
            if (decl.name != "Companion") return decl.name
            val owner = enclosing(decl.file, decl.start).firstOrNull()?.name ?: "?"
            return "$owner.Companion"
        }

        /** Singleton class names and provided singleton type names, with a file. */
        fun singletons(): List<Pair<String, String>> {
            val fromClasses = classes.filter { it.kind != "object" && it.has("Singleton") }.map { it.name to it.file }
            val provided = functions.filter { it.has("Singleton") && (it.has("Provides") || it.has("Binds")) }
                .map { it.returnName.ifEmpty { it.name } to it.file }
            return (fromClasses + provided).distinctBy { it.first }
        }

        fun declaredNames(): Set<String> =
            classes.map { it.name }.toSet() +
                functions.filter { it.has("Provides") || it.has("Binds") }.map { it.returnName }

        fun scopedClasses(): Set<String> =
            classes.filter { it.has("IdentityScoped") && it.kind == "class" && !it.has("DefineComponent") }.map { it.name }.toSet()

        /** `@IdentityScoped` classes plus the types `@CurrentIdentity` bridges hand out. */
        fun identityTypes(): Set<String> = identityCache

        private val identityCache: Set<String> by lazy {
            scopedClasses() + bridges().map { it.returnName }.filter { it.isNotEmpty() }
        }

        fun bridges(): List<FunDecl> = functions.filter { it.has("CurrentIdentity") && (it.has("Provides") || it.has("Binds")) }

        fun chatSessionNames(): Set<String> =
            classes.filter { it.has("ActivityRetainedScoped") || it.has("HiltViewModel") }.map { it.name }.toSet() +
                functions.filter { isChatSessionFunction(it) }.map { it.returnName }

        /** A `@Provides` is chat-session when scoped so, or when its module is installed in a chat-session component. */
        fun isChatSessionFunction(fn: FunDecl): Boolean =
            fn.has("ActivityRetainedScoped") || enclosing(fn.file, fn.index).firstOrNull()?.let {
                Regex("""@InstallIn\(\s*(?:ActivityRetainedComponent|ViewModelComponent)::class""").containsMatchIn(it.annotations)
            } == true

        private fun exempt(file: String, index: Int): Boolean =
            enclosing(file, index).any {
                it.has("IdentityScoped") || Regex("""@InstallIn\(\s*IdentityComponent::class""").containsMatchIn(it.annotations)
            }

        /** Parameter lists of the `@Inject constructor(...)` members declared directly in [decl]'s body. */
        fun secondaryInjectConstructors(decl: ClassDecl): List<String> {
            if (!decl.hasBody) return emptyList()
            val code = codeOf(decl.file)
            val modifier = """(?:(?:internal|private|public|protected)\s+)?"""
            return Regex("""$modifier@(?:[a-z]\w*\.)*(?:Assisted)?Inject\s*${modifier}constructor\s*\(""")
                .findAll(code.substring(0, decl.bodyEnd))
                .filter { it.range.first > decl.bodyStart && enclosing(decl.file, it.range.first).firstOrNull() === decl }
                .mapNotNull { m -> closing(code, m.range.last).takeIf { it > 0 }?.let { code.substring(m.range.last + 1, it) } }
                .toList()
        }

        fun sites(): List<Site> {
            val out = mutableListOf<Site>()
            // Constructors of classes the graph builds.
            classes.filter { it.kind == "class" }.forEach { decl ->
                if (exempt(decl.file, decl.start) || decl.has("IdentityScoped")) return@forEach
                val primaryDi = decl.ctor != null &&
                    (Regex("""@(?:[a-z]\w*\.)*(?:Assisted)?Inject\b""").containsMatchIn(decl.ctorPrefix) || decl.has("HiltViewModel"))
                // An `@Inject` secondary constructor is the one the graph calls; the primary is then plain Kotlin.
                val injected = secondaryInjectConstructors(decl).ifEmpty { listOfNotNull(decl.ctor.takeIf { primaryDi }) }
                val chat = decl.has("ActivityRetainedScoped") || decl.has("HiltViewModel")
                injected.forEach { ctor ->
                    params(ctor).forEach {
                        out += Site(decl.file, decl.name, chat, Kind.CONSTRUCTOR, "constructor parameter `${it.name}`", decl.name, it.annotations, it.type)
                    }
                }
            }
            // `@Inject` fields.
            sources.forEach { src ->
                Regex("""@(?:\w+:)?Inject\b((?:\s|@(?:\w+:)?\w+(?:\([^)]*\))?|lateinit|private|internal|public)*)\b(?:var|val)\s+(\w+)\s*:\s*([^\n=;}]+)""").findAll(src.code).forEach { m ->
                    if (exempt(src.path, m.range.first)) return@forEach
                    val ownerDecl = enclosing(src.path, m.range.first).firstOrNull()
                    val chat = ownerDecl?.let { it.has("ActivityRetainedScoped") || it.has("HiltViewModel") } == true
                    out += Site(
                        src.path, ownerDecl?.name ?: "<top level>", chat, Kind.FIELD, "@Inject field `${m.groupValues[2]}`",
                        ownerDecl?.name ?: "<top level>", m.groupValues[1], m.groupValues[3].trim(),
                    )
                }
            }
            // Entry-point accessors, and `@Provides` parameters and results.
            functions.forEach { fn ->
                val enclosing = enclosing(fn.file, fn.index)
                if (exempt(fn.file, fn.index)) return@forEach
                val inEntryPoint = enclosing.firstOrNull()?.has("EntryPoint") == true
                if (inEntryPoint) {
                    out += Site(fn.file, enclosing.first().name, false, Kind.ENTRY_POINT, "entry-point function `${fn.name}`", enclosing.first().name, fn.annotations, fn.returnType)
                } else if (fn.has("Provides")) {
                    val chat = isChatSessionFunction(fn)
                    val owner = fn.returnName.ifEmpty { fn.name }
                    val module = enclosing.firstOrNull()?.name ?: "<top level>"
                    params(fn.params).forEach {
                        out += Site(fn.file, owner, chat, Kind.PROVIDES_PARAM, "@Provides parameter `${it.name}` of `${fn.name}`", module, it.annotations, it.type)
                    }
                    out += Site(fn.file, owner, chat, Kind.PROVIDES_RETURN, "@Provides function `${fn.name}` result", module, fn.annotations, fn.returnType)
                }
            }
            return out
        }
    }

    // ---- Parsing ----------------------------------------------------------------------------

    private class Param(val name: String, val annotations: String, val type: String)

    private companion object {
        val annotationToken = Regex("""@(?:\w+:)?[\w.]+(?:\([^)]*\))?\s*""")
        val statePattern = Regex(
            """\bMutable\w*|\bmutable\w+Of\b|\bConcurrentHashMap\b|\bAtomic\w+|\bCopyOnWrite\w+|\bHashMap\b|\bHashSet\b|\bWeakHashMap\b|\bnewSetFromMap\b|""" +
                """\bArrayList\b|\bArrayDeque\b|\bLinkedHashMap\b|\bLruCache\b|\bCollections\.synchronized\w+""",
        )

        fun baseName(type: String): String =
            Regex("""[A-Za-z_][\w.]*""").find(type.trim())?.value?.substringAfterLast('.').orEmpty()

        /** The `T` in `Provider<T>` (javax, dagger or any package), or null. */
        fun providerOf(type: String): String? =
            Regex("""^Provider\s*<\s*(\w+)\s*>$""").find(type.trim().replace(Regex("""\b[a-z]\w*\."""), ""))?.groupValues?.get(1)

        /** Drops comments and the contents of string and char literals, keeping every offset and newline. */
        fun mask(src: String): String {
            val out = StringBuilder(src.length)
            fun keepNewline(c: Char) = if (c == '\n') '\n' else ' '
            var i = 0
            while (i < src.length) {
                val c = src[i]
                when {
                    src.startsWith("//", i) -> while (i < src.length && src[i] != '\n') { out.append(' '); i++ }
                    src.startsWith("/*", i) -> {
                        var depth = 0
                        do {
                            when {
                                src.startsWith("/*", i) -> { depth++; out.append("  "); i += 2 }
                                src.startsWith("*/", i) -> { depth--; out.append("  "); i += 2 }
                                else -> { out.append(keepNewline(src[i])); i++ }
                            }
                        } while (i < src.length && depth > 0)
                    }
                    src.startsWith("\"\"\"", i) -> {
                        out.append("\"\"\""); i += 3
                        while (i < src.length && !src.startsWith("\"\"\"", i)) { out.append(keepNewline(src[i])); i++ }
                        if (i < src.length) { out.append("\"\"\""); i += 3 }
                    }
                    c == '"' -> {
                        out.append('"'); i++
                        while (i < src.length && src[i] != '"' && src[i] != '\n') {
                            if (src[i] == '\\' && i + 1 < src.length) { out.append("  "); i += 2 } else { out.append(' '); i++ }
                        }
                        if (i < src.length && src[i] == '"') { out.append('"'); i++ }
                    }
                    c == '\'' && Regex("""'(?:\\.|[^'\\\n])'""").matchesAt(src, i) -> {
                        val end = src.indexOf('\'', i + 2 + if (src[i + 1] == '\\') 1 else 0)
                        out.append('\''); repeat(end - i - 1) { out.append(' ') }; out.append('\''); i = end + 1
                    }
                    else -> { out.append(c); i++ }
                }
            }
            return out.toString()
        }

        /** Index of the bracket closing the one at [open], or -1. */
        fun closing(text: String, open: Int): Int {
            val up = text[open]
            val down = when (up) { '(' -> ')'; '{' -> '}'; '[' -> ']'; else -> error("not a bracket: $up") }
            var depth = 0
            for (i in open until text.length) {
                if (text[i] == up) depth++
                if (text[i] == down && --depth == 0) return i
            }
            return -1
        }

        /** Splits at commas that are not inside brackets or generics. */
        fun splitTopLevel(text: String): List<String> {
            val parts = mutableListOf<String>()
            var depth = 0
            var from = 0
            text.forEachIndexed { i, c ->
                when {
                    c in "([{<" -> depth++
                    c == '>' && i > 0 && text[i - 1] == '-' -> Unit
                    c in ")]}>" -> depth--
                    c == ',' && depth == 0 -> { parts += text.substring(from, i); from = i + 1 }
                }
            }
            parts += text.substring(from)
            return parts.map { it.trim() }.filter { it.isNotEmpty() }
        }

        fun params(text: String): List<Param> = splitTopLevel(text).mapNotNull { rawWithAnnotations ->
            val annotations = annotationToken.findAll(rawWithAnnotations).joinToString(" ") { it.value }
            val raw = rawWithAnnotations.replace(annotationToken, "")
            var depth = 0
            var eq = raw.length
            var colon = -1
            raw.forEachIndexed { i, c ->
                when {
                    c in "([{<" -> depth++
                    c == '>' && i > 0 && raw[i - 1] == '-' -> Unit
                    c in ")]}>" -> depth--
                    c == ':' && depth == 0 && colon < 0 && eq == raw.length &&
                        raw.getOrNull(i + 1) != ':' && raw.getOrNull(i - 1) != ':' -> colon = i
                    c == '=' && depth == 0 && eq == raw.length && raw.getOrNull(i + 1) != '=' -> eq = i
                }
            }
            if (colon < 0 || colon > eq) return@mapNotNull null
            val name = Regex("""(\w+)\s*$""").find(raw.substring(0, colon))?.groupValues?.get(1) ?: return@mapNotNull null
            Param(name, annotations, raw.substring(colon + 1, eq).trim())
        }

        /** Annotations and modifiers written before the declaration keyword at [index]. */
        fun annotationsBefore(code: String, index: Int): String {
            var lineStart = code.lastIndexOf('\n', index - 1) + 1
            var acc = code.substring(lineStart, index)
            val cut = acc.lastIndexOfAny(charArrayOf('{', '}', ';'))
            if (cut >= 0) return acc.substring(cut + 1)
            while (lineStart > 0) {
                val prevStart = code.lastIndexOf('\n', lineStart - 2) + 1
                val line = code.substring(prevStart, lineStart - 1)
                val unbalanced = acc.count { it == ')' } > acc.count { it == '(' }
                if ((line.trim().startsWith("@") && line.replace(annotationToken, "").isBlank()) || (unbalanced && line.isNotBlank())) {
                    acc = line + "\n" + acc
                    lineStart = prevStart
                } else {
                    break
                }
            }
            return acc
        }

        fun parseClasses(src: Src): List<ClassDecl> {
            val code = src.code
            return Regex("""\b(class|interface|object)\b[ \t]*(\w*)""").findAll(code).mapNotNull { m ->
                if (code.substring(0, m.range.first).endsWith("::")) return@mapNotNull null
                val kind = if (m.groupValues[1] == "interface") "class" else m.groupValues[1]
                val before = code.substring(code.lastIndexOf('\n', m.range.first - 1) + 1, m.range.first)
                var name = m.groupValues[2]
                if (kind == "object" && name.isEmpty()) {
                    if (!before.trimEnd().endsWith("companion")) return@mapNotNull null
                    name = "Companion"
                }
                if (name.isEmpty() || name == "constructor") return@mapNotNull null
                var pos = m.range.last + 1
                // Generics, then an optional primary constructor with its own annotations.
                Regex("""\G\s*<[^>]*(?:<[^>]*>[^>]*)*>""").find(code, pos)?.let { pos = it.range.last + 1 }
                val prefix = Regex("""\G\s*((?:@[\w.]+(?:\([^)]*\))?|private|internal|public|protected)\s*)*(?:constructor)?\s*(?=\()""")
                    .find(code, pos)
                var ctor: String? = null
                var ctorPrefix = ""
                if (prefix != null && kind == "class") {
                    val open = prefix.range.last + 1
                    val close = closing(code, open)
                    if (close > 0) {
                        ctor = code.substring(open + 1, close)
                        ctorPrefix = prefix.value
                        pos = close + 1
                    }
                }
                val bodyStart = bodyBrace(code, pos)
                val bodyEnd = if (bodyStart >= 0) closing(code, bodyStart) else -1
                ClassDecl(
                    src.path, kind, name, annotationsBefore(code, m.range.first), ctorPrefix, ctor,
                    m.range.first, if (bodyEnd < 0) -1 else bodyStart, bodyEnd,
                )
            }.toList()
        }

        /** The `{` opening a declaration's body after [from], or -1 when the declaration has none. */
        fun bodyBrace(code: String, from: Int): Int {
            var depth = 0
            var i = from
            var lastSignificant = ' '
            while (i < code.length) {
                val c = code[i]
                when {
                    c in "(<[" -> depth++
                    c == '>' && i > 0 && code[i - 1] == '-' -> Unit
                    c in ")>]" -> depth--
                    c == '{' && depth <= 0 -> return i
                    c == '\n' && depth <= 0 -> {
                        var k = i
                        while (k < code.length && code[k].isWhitespace()) k++
                        val next = code.getOrNull(k) ?: return -1
                        if (lastSignificant !in ":," && next !in ":,{") return -1
                    }
                    c == '=' && depth <= 0 && code.getOrNull(i + 1) != '=' -> return -1
                    c == ';' && depth <= 0 -> return -1
                }
                if (!c.isWhitespace()) lastSignificant = c
                i++
            }
            return -1
        }

        fun parseFunctions(src: Src): List<FunDecl> {
            val code = src.code
            return Regex("""\bfun\b\s*(?:<[^>]*>\s*)?(?:[\w.<>?]+\.)?(\w+)\s*\(""").findAll(code).mapNotNull { m ->
                val open = m.range.last
                val close = closing(code, open)
                if (close < 0) return@mapNotNull null
                val tail = code.substring(close + 1)
                val ret = Regex("""^\s*:\s*([^{=\n;}]+)""").find(tail)?.groupValues?.get(1)?.trim().orEmpty()
                FunDecl(
                    src.path, m.groupValues[1], annotationsBefore(code, m.range.first), code.substring(open + 1, close),
                    ret, m.range.first,
                )
            }.toList()
        }

        /**
         * Splits a body into its direct members. A member starts at the first line at bracket depth 0
         * that does not continue the one before it.
         */
        fun members(body: String): List<String> {
            val out = mutableListOf<String>()
            var depth = 0
            var current = StringBuilder()
            var lastSignificant = ' '
            body.lines().forEach { line ->
                val t = line.trim()
                val continues = depth > 0 || t.isEmpty() || lastSignificant in "=,(&|+:." ||
                    Regex("""^(?:\.|\?\.|\?:|&&|\|\||\)|\}|else\b|by\b)""").containsMatchIn(t) || current.isBlank()
                if (!continues && current.isNotBlank()) {
                    out += current.toString(); current = StringBuilder()
                }
                current.appendLine(line)
                line.forEach { c ->
                    when {
                        c in "({[" -> depth++
                        c in ")}]" -> depth--
                    }
                }
                if (t.isNotEmpty()) lastSignificant = t.last()
            }
            if (current.isNotBlank()) out += current.toString()
            return out
        }

        /** Text of [member] with everything inside braces removed. */
        fun withoutBraces(member: String): String {
            var depth = 0
            val sb = StringBuilder()
            member.forEach { c ->
                if (c == '}') depth--
                if (depth == 0) sb.append(c)
                if (c == '{') depth++
            }
            return sb.toString()
        }

        fun isStatefulMember(member: String): Boolean {
            val flat = withoutBraces(member).trim()
            val header = Regex("""^((?:@\w+(?:\([^)]*\))?|\w+)\s+)*?(val|var|fun|init|class|object|companion|const)\b""")
                .find(flat)?.groupValues?.get(2) ?: return false
            return when (header) {
                "var" -> true
                "val" -> statePattern.containsMatchIn(flat)
                else -> false
            }
        }

        /** Callees whose lambda runs later, not while the instance is built. Everything else counts as eager. */
        val deferredCallee = Regex("""\b(?:lazy|launch|async)\s*(?:\([^)]*\))?\s*\{""")

        /** Code that runs while an instance is built: initialisers and init blocks, not functions, getters or lazies. */
        fun constructionCode(body: String): String = members(body).mapNotNull { member ->
            val flat = withoutBraces(member).trim()
            val keyword = Regex("""^(?:(?:@\w+(?:\([^)]*\))?|\w+)\s+)*?(val|var|fun|init|class|object|companion)\b""")
                .find(flat)?.groupValues?.get(1)
            when (keyword) {
                "init" -> withoutDeferredBodies(member)
                "val", "var" ->
                    if (Regex("""\bby\b|\bget\s*\(""").containsMatchIn(flat.substringBefore("="))) null
                    else withoutDeferredBodies(member)
                else -> null
            }
        }.joinToString("\n")

        /** Drops the lambdas handed to [deferredCallee]; any other lambda is kept, because it may run now. */
        fun withoutDeferredBodies(text: String): String {
            var result = text
            while (true) {
                val m = deferredCallee.find(result) ?: return result
                val open = m.range.last
                val close = closing(result, open).takeIf { it > 0 } ?: return result
                result = result.substring(0, open) + result.substring(close + 1)
            }
        }
    }

    // ---- Reading the repository -------------------------------------------------------------

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun modelOf(vararg sources: String): Model =
        Model(sources.mapIndexed { i, s -> Src("Synthetic$i.kt", mask(s)) })

    private fun realModel(): Model {
        val files = listOf("app", "core", "feature", "wear").flatMap { top ->
            File(root, top).walkTopDown()
                .onEnter { it.name != "build" && it.name != ".gradle" }
                .filter { it.isFile && it.extension == "kt" }
                .filter { Regex("""(^|/)src/(main|debug|release)/""").containsMatchIn(it.relativeTo(root).path.replace(File.separatorChar, '/')) }
                .toList()
        }
        assertTrue("Found no sources under $root; the scan is broken", files.size > 100)
        return Model(files.map { Src(it.relativeTo(root).path.replace(File.separatorChar, '/'), mask(it.readText())) })
    }
}
