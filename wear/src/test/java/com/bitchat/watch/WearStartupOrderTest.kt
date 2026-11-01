package com.bitchat.watch

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The watch's start-up work keeps today's order: the power policy, then the notification
 * coordinator (which creates the notification channel), then the peer identity state (which
 * initialises the favourites). Read from the source with comments dropped: running `onCreate`
 * needs a real Application.
 */
class WearStartupOrderTest {

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun onCreate_initialisesPowerNotificationsPeerIdentityInOrder() {
        val onCreate = File(root, "wear/src/main/java/com/bitchat/watch/BitchatWatchApplication.kt")
            .readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }
            .substringAfter("override fun onCreate()")
        val steps = linkedMapOf(
            "power policy" to Regex("""powerManager\.get\(\)"""),
            "notification coordinator" to Regex("""notificationCoordinator\.get\(\)"""),
            "peer identity" to Regex("""peerIdentity\.get\(\)\.initialize\(applicationContext\)"""),
        )
        val positions = steps.map { (name, pattern) ->
            val hits = pattern.findAll(onCreate).map { it.range.first }.toList()
            assertEquals("BitchatWatchApplication.onCreate must run $name exactly once", 1, hits.size)
            name to hits.single()
        }
        assertEquals(
            "Start-up order changed: ${steps.keys}",
            positions.sortedBy { it.second }.map { it.first },
            positions.map { it.first },
        )
    }
}
