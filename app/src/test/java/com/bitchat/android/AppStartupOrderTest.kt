package com.bitchat.android

import android.content.Context
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.nostr.LocationNotesInitializer
import com.bitchat.android.nostr.PoWPreferenceManager
import java.io.File
import javax.inject.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock

/**
 * The eager Tor and Nostr start-up work keeps today's order:
 *
 * - `BitchatApplication.onCreate`: Tor init, relay directory initialize, location notes
 *   initialize, background runtime initialize. The Nostr HTTP clients are no longer installed
 *   here: `NostrNetworkModule` binds them, so they exist before anything that needs them.
 * - `DefaultAppInitializer.initializeLibraries`: PoW load, then location notes initialize.
 *
 * The initializer is checked at runtime with fakes. `onCreate` is checked in the source, comments
 * dropped: the Tor and Nostr steps are injected and fakeable now, but running `onCreate` would
 * also start the power policy, the mesh foreground service and the other start-up work that is
 * still static, so it cannot run in a JVM test.
 */
class AppStartupOrderTest {

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun initializeLibraries_initializesPowBeforeLocationNotes() {
        val context = mock<Context>()
        val pow = mock<PoWPreferenceManager>()
        val notes = mock<LocationNotesInitializer>()
        val initializer = DefaultAppInitializer(
            context = context,
            meshService = Provider<MeshService> { error("not started here") },
            powPreferences = pow,
            locationNotesInitializer = notes,
        )

        initializer.initializeLibraries()

        inOrder(pow, notes) {
            verify(pow).load()
            verify(notes).initialize(context)
            verifyNoMoreInteractions()
        }
    }

    @Test
    fun applicationOnCreate_startsTorThenNostrInTodaysOrder() {
        val source = File(root, "app/src/main/java/com/bitchat/android/BitchatApplication.kt")
            .readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }
        val onCreate = source.substringAfter("override fun onCreate()")
        val steps = listOf(
            "torProvider.init(this)",
            "relayDirectory.get().initialize(this)",
            "locationNotesInitializer.get().initialize(this)",
            "nostrBackgroundRuntime.get().initialize(",
        )
        val positions = steps.map { step ->
            val at = onCreate.indexOf(step)
            assertTrue("BitchatApplication.onCreate no longer calls $step", at >= 0)
            assertEquals("$step appears more than once", at, onCreate.lastIndexOf(step))
            at
        }
        assertEquals("Start-up order changed: $steps", positions.sorted(), positions)
        assertFalse(
            "onCreate builds Nostr HTTP clients again; NostrNetworkModule binds them",
            "NostrHttpClients" in onCreate
        )
    }

    /**
     * The mesh preference stores are loaded in the order `onCreate` loads them today: favourites,
     * private-conversation persistence, Nostr identity, spam filter preference, debug preferences,
     * Wi-Fi Aware controller, transport toggles, geohash registries, then the mesh-service
     * preferences. Each reads stored state the next one (or a transport started after it) relies
     * on, and none of them may be resolved earlier than its own line.
     */
    @Test
    fun onCreate_initialisesMeshPreferencesInTodaysOrder() {
        val onCreate = applicationOnCreate()
        val steps = linkedMapOf(
            "power policy" to Regex("""powerManager\.get\(\)\.start\(\)"""),
            "favourites" to Regex("""favoritesInit\.initialize\(\)"""),
            "conversation persistence" to Regex("""appStateStore\.get\(\)\.initializeConversationPersistence\(this\)"""),
            "nostr identity" to Regex("""nostrIdentityBridge\.get\(\)\.getCurrentNostrIdentity\(this\)"""),
            "spam preferences" to Regex("""spamFilterPreferences\.get\(\)\.init\(this\)"""),
            "debug preferences" to Regex("""debugPreferences\.get\(\)\.init\(this\)"""),
            "wifi aware" to Regex("""wifiAwareController\.get\(\)\.initialize\(this,"""),
            "transport toggles" to Regex("""debugSettings\.get\(\)\.transportToggles"""),
            "geohash aliases" to Regex("""geohashAliases\.get\(\)\.initialize\(this\)"""),
            "geohash conversations" to Regex("""geohashConversations\.get\(\)\.initialize\(this\)"""),
            "mesh service preferences" to Regex("""meshServicePreferences\.get\(\)\.init\(this\)"""),
        )
        val positions = steps.map { (name, pattern) ->
            val hits = pattern.findAll(onCreate).map { it.range.first }.toList()
            assertEquals("BitchatApplication.onCreate must run $name exactly once", 1, hits.size)
            name to hits.single()
        }
        assertEquals(
            "Start-up order changed: ${steps.keys}",
            positions.sortedBy { it.second }.map { it.first },
            positions.map { it.first },
        )
    }

    /** Hilt injects the Application's fields before `onCreate`'s own lines, so each must wait for its line. */
    @Test
    fun applicationInjectsEverythingLazily() {
        val source = File(root, "app/src/main/java/com/bitchat/android/BitchatApplication.kt").readText()
        val eager = Regex("""@Inject lateinit var (\w+): (?!Lazy<)(\w+)""").findAll(source)
            .map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertEquals(
            "Only the init bridge may be injected eagerly (it only holds a Context): $eager",
            listOf("favoritesInit" to "FavoritesInitRetry"),
            eager,
        )
    }

    private fun applicationOnCreate(): String =
        File(root, "app/src/main/java/com/bitchat/android/BitchatApplication.kt")
            .readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }
            .substringAfter("override fun onCreate()")
}
