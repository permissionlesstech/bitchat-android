package com.bitchat.android.architecture

import com.bitchat.android.panic.KeepList
import com.bitchat.android.panic.KeptPrefs
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keep-list names preferences files and keys that exist elsewhere as string literals. A key
 * renamed at its owner but not here would silently be wiped (or kept, if the old name stuck), so
 * each name has to appear as a literal in a main source other than the list itself.
 */
class KeepListRulesTest {

    @Test
    fun everyKeptPrefsFileAndKeyIsWrittenSomewhere() {
        val literals = literalsInMainSources()
        val missing = missing(KeepList.prefs, literals)
        assertTrue(
            "These kept names appear in no main source as a string literal. Rename them here too:\n" +
                missing.joinToString("\n"),
            missing.isEmpty(),
        )
    }

    @Test
    fun theMatcherSeesARenamedFileKeyAndPrefix() {
        val literals = setOf("file_a", "key_one", "prefix_")
        val kept = listOf(
            KeptPrefs("file_a", setOf("key_one"), setOf("prefix_"), "synthetic"),
            KeptPrefs("file_renamed", setOf("key_renamed"), setOf("other_prefix_"), "synthetic"),
        )
        assertEquals(
            listOf("file_renamed (file)", "file_renamed key key_renamed", "file_renamed prefix other_prefix_"),
            missing(kept, literals),
        )
    }

    @Test
    fun aPrefixIsMatchedByALiteralThatStartsWithIt() {
        val kept = listOf(KeptPrefs("f", emptySet(), setOf("pre_"), "synthetic"))
        assertTrue(missing(kept, setOf("f", "pre_")).isEmpty())
        assertTrue(missing(kept, setOf("f", "pre_x")).isEmpty())
        assertEquals(1, missing(kept, setOf("f", "xpre_")).size)
    }

    private fun missing(kept: List<KeptPrefs>, literals: Set<String>): List<String> = kept.flatMap { prefs ->
        buildList {
            if (prefs.file !in literals) add("${prefs.file} (file)")
            prefs.keys.filter { it !in literals }.forEach { add("${prefs.file} key $it") }
            prefs.keyPrefixes.filter { prefix -> literals.none { it.startsWith(prefix) } }.forEach { add("${prefs.file} prefix $it") }
        }
    }

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    /** Every string literal in the main sources, except those in KeepList.kt, which would match itself. */
    private fun literalsInMainSources(): Set<String> {
        val files = listOf("app", "core", "feature", "wear").flatMap { top ->
            File(root, top).walkTopDown()
                .onEnter { it.name != "build" && it.name != ".gradle" }
                .filter { it.isFile && it.extension == "kt" && it.name != "KeepList.kt" }
                .filter { it.relativeTo(root).path.replace(File.separatorChar, '/').contains("/src/main/") }
                .toList()
        }
        assertTrue("Found no sources under $root; the scan is broken", files.size > 100)
        val literal = Regex(""""((?:\\.|[^"\\\n])*)"""")
        return files.flatMap { f -> literal.findAll(f.readText()).map { it.groupValues[1] }.toList() }.toSet()
    }
}
