package com.bitchat.watch

import java.io.File
import org.junit.Assert.assertEquals
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
        val eager = Regex("""@Inject lateinit var (\w+): (?!Lazy<)(\w+)""").findAll(source)
            .map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertEquals("Every Application field must be injected Lazy: $eager", emptyList<Pair<String, String>>(), eager)
    }
}
