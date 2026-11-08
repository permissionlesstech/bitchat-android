package com.bitchat.watch

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hilt injects the Application's fields before `onCreate`'s own lines, so each must wait for its
 * line. Read from the source: running the Application needs a real device.
 */
class WearApplicationInjectsLazilyTest {

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun applicationInjectsEverythingLazily() {
        val source = File(root, "wear/src/main/java/com/bitchat/watch/BitchatWatchApplication.kt").readText()
        val eager = Regex("""@Inject lateinit var (\w+): (?!Lazy<|Provider<)(\w+)""").findAll(source)
            .map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertEquals("Every Application field must be injected Lazy or as a Provider: $eager", emptyList<Pair<String, String>>(), eager)
    }

    private val identityTypes = listOf("WearMeshHolder", "WearMeshService", "WearChatState", "WearPeerIdentityState", "AppStateStore")

    private fun code(path: String): String =
        File(root, path).readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .lines().joinToString("\n") { it.substringBefore("//") }

    private val processLived = listOf(
        "wear/src/main/java/com/bitchat/watch/BitchatWatchApplication.kt",
        "wear/src/main/java/com/bitchat/watch/MainActivity.kt",
        "wear/src/main/java/com/bitchat/watch/service/WearMeshForegroundService.kt",
        "wear/src/main/java/com/bitchat/watch/notification/WearNotificationCoordinator.kt",
    )

    @Test
    fun processLivedClassesTakeIdentityTypesAsCurrentIdentityProviders() {
        val names = identityTypes.joinToString("|")
        processLived.forEach { path ->
            val source = code(path)
            // `private lateinit var mesh` is the service's own copy of what the holder built, not an injection.
            val direct = Regex("""[^\n]*(?:lateinit var|val|var)\s+\w+:\s*($names)\b""").findAll(source)
                .map { it.value.trim() }.filterNot { it.startsWith("private lateinit var") }.toList()
            assertEquals("$path takes an identity type directly: $direct", emptyList<String>(), direct)
            val unqualified = Regex("""[^\n]*Provider<($names)>""").findAll(source)
                .map { it.value }.filter { "@CurrentIdentity" !in it }.toList()
            assertEquals("$path has an unqualified Provider of an identity type: $unqualified", emptyList<String>(), unqualified)
        }
        assertTrue(
            "The Activity no longer takes the mesh holder as a Provider; the rule checks nothing",
            Regex("""@CurrentIdentity lateinit var meshHolder: Provider<WearMeshHolder>""")
                .containsMatchIn(code(processLived[1])),
        )
    }

    @Test
    fun nothingReadsTheIdentityBeforeThePermissionsAreConfirmed() {
        val reads = Regex("""(?:meshHolder|chatState|peerIdentity|appStateStore)\.get\(\)""")
        val activity = code(processLived[1])
        val beforeSetContent = activity.substringAfter("override fun onCreate").substringBefore("setContent")
        assertFalse("MainActivity.onCreate reads an identity type before the screens: ${reads.find(beforeSetContent)?.value}", reads.containsMatchIn(beforeSetContent))
        val fields = activity.substringBefore("override fun onCreate")
        assertFalse("A MainActivity field initialiser reads the identity", reads.containsMatchIn(fields))
        val gate = activity.substringAfter("!hasPermissions ->").substringBefore("else -> WearNavHost")
        assertTrue("The mesh is created in the nickname arm only", reads.findAll(gate).all { it.value.startsWith("meshHolder") })
        val coordinator = code(processLived[3])
        val beforeFirstFunction = coordinator.substringBefore("fun onPrivateMessage")
        assertFalse("WearNotificationCoordinator reads the chat state at construction", Regex("""chatState\.get\(\)""").containsMatchIn(beforeFirstFunction))
        val service = code(processLived[2])
        assertFalse("The service reads the chat state in a field initialiser", Regex("""chatState\.get\(\)""").containsMatchIn(service.substringBefore("override fun onCreate")))
    }

    @Test
    fun noWatchSourceReadsTheIdentityHolderDirectly() {
        val offenders = File(root, "wear/src/main").walkTopDown().filter { it.extension == "kt" }
            .filter { "IdentityHolder" in it.readText() && it.name != "WearIdentityBindings.kt" }.map { it.name }.toList()
        assertEquals("Only the bindings may read IdentityHolder", emptyList<String>(), offenders)
    }
}
