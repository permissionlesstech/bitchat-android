package com.bitchat.android.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A broadcast receiver runs with no activity and may be the first code in a cold process after
 * the Application, so one that needs a mesh singleton must get it from Hilt: either by being an
 * `@AndroidEntryPoint` or by looking an `@EntryPoint` up. Reaching the type statically would
 * bypass the graph. Read from the manifests and the receivers' sources, comments dropped.
 */
class ReceiverInjectionTest {

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    /** The types that come from the graph (or are bridged into it until their flip). */
    private val injectedTypes = listOf(
        "PowerManager", "PeerFingerprintManager", "DebugSettingsManager", "SpamFilter",
        "FavoritesPersistenceService", "LiveVoiceManager", "SeenMessageStore", "ConversationRepository",
        "MeshGraphService", "AppStateStore", "ContactDirectory", "TransportBridgeService",
        "PrivateMessageArrivalOrder", "VoiceWaveformCache", "SpamFilterPreferences",
        "TransferProgressManager", "DebugPreferenceManager", "GeohashAliasRegistry",
        "VerificationService", "WifiAwareController", "MeshServiceHolder", "MeshServicePreferences",
        "ConversationListPreferences", "NostrIdentityBridge", "GeohashConversationRegistry",
        "TorPreferenceManager", "WearChatState", "WearPeerIdentityState", "WearNotificationCoordinator",
        "WearMeshService",
    )

    private val manifests = listOf(
        "app/src/main/AndroidManifest.xml",
        "app/src/debug/AndroidManifest.xml",
        "wear/src/main/AndroidManifest.xml",
        "wear/src/debug/AndroidManifest.xml",
    )

    @Test
    fun receiversThatUseInjectedTypes_areEntryPoints() {
        val receivers = declaredReceivers()
        val names = receivers.map { it.substringAfterLast('.') }
        assertTrue("Found no receivers; the manifests moved: $manifests", receivers.isNotEmpty())
        assertTrue("BootCompletedReceiver is no longer scanned: $names", "BootCompletedReceiver" in names)
        assertTrue("ConversationNotificationReceiver is no longer scanned: $names", "ConversationNotificationReceiver" in names)

        val offenders = receivers.mapNotNull { receiver ->
            val file = sourceOf(receiver) ?: return@mapNotNull "$receiver: no source file found"
            val code = file.readText()
                .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
                .lines()
                .filterNot { it.trimStart().startsWith("import ") }
                .joinToString("\n") { it.substringBefore("//") }
            val used = injectedTypes.filter { Regex("""(?<![\w@])$it\b""").containsMatchIn(code) }
            val viaGraph = "@AndroidEntryPoint" in code || "EntryPointAccessors" in code
            if (used.isNotEmpty() && !viaGraph) {
                "${file.relativeTo(root).invariantSeparatorsPath} reaches $used without @AndroidEntryPoint or an EntryPointAccessors lookup"
            } else null
        }
        assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
    }

    /** Fully qualified class names of every `<receiver>` the manifests declare. */
    private fun declaredReceivers(): List<String> = manifests.flatMap { path ->
        val text = File(root, path).takeIf { it.isFile }?.readText() ?: return@flatMap emptyList()
        val pkg = Regex("""package="([^"]+)"""").find(text)?.groupValues?.get(1)
            ?: namespaceOf(path)
        Regex("""<receiver[^>]*?android:name="([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text)
            .map { it.groupValues[1] }
            .map { if (it.startsWith(".")) pkg + it else it }
            .toList()
    }

    /** Debug manifests carry no package; the module's namespace is its main package. */
    private fun namespaceOf(path: String): String = when {
        path.startsWith("app/") -> "com.bitchat.android"
        else -> "com.bitchat.watch"
    }

    private fun sourceOf(fqcn: String): File? {
        val rel = fqcn.replace('.', '/') + ".kt"
        return listOf("app", "wear")
            .flatMap { module -> listOf("main", "debug").map { File(root, "$module/src/$it/java/$rel") } }
            .firstOrNull { it.isFile }
    }
}
