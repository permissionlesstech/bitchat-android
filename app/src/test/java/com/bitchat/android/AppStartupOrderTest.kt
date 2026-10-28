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
}
